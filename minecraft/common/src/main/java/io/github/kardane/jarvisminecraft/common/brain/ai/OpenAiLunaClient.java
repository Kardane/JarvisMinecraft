package io.github.kardane.jarvisminecraft.common.brain.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;

import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class OpenAiLunaClient implements LunaClient {
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final OpenAIClientAsync client;
    private final boolean ownsClient;
    private final LunaToolSchemas toolSchemas;
    private final Clock clock;
    private final Gson gson;
    private final Map<UUID, RequestState> states = new LinkedHashMap<>();

    public OpenAiLunaClient(String apiKey, ToolArgumentCodec argumentCodec) {
        this(
            createClient(apiKey),
            true,
            argumentCodec,
            Clock.systemUTC()
        );
    }

    public OpenAiLunaClient(
        OpenAIClientAsync client,
        ToolArgumentCodec argumentCodec,
        Clock clock
    ) {
        this(client, false, argumentCodec, clock);
    }

    private OpenAiLunaClient(
        OpenAIClientAsync client,
        boolean ownsClient,
        ToolArgumentCodec argumentCodec,
        Clock clock
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.toolSchemas = new LunaToolSchemas(
            Objects.requireNonNull(argumentCodec, "argumentCodec")
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantJsonAdapter())
            .create();
    }

    @Override
    public String modelId() {
        return LunaPrompt.MODEL;
    }

    @Override
    public CompletionStage<LunaStep> next(
        LunaTurnInput input,
        DeterministicRoutePolicy.RoutingDecision routing
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(routing, "routing");

        try {
            RequestState state = stateFor(input);
            appendFunctionOutputs(state, input);

            Duration remaining = Duration.between(clock.instant(), input.deadlineAt());
            Duration timeout = remaining.compareTo(MAX_TIMEOUT) < 0
                ? remaining
                : MAX_TIMEOUT;
            if (timeout.isZero() || timeout.isNegative()) {
                clear(input.requestId());
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Luna request deadline has expired.")
                );
            }

            ResponseCreateParams params = createParams(input, routing, state);
            RequestOptions requestOptions = RequestOptions.builder()
                .timeout(timeout)
                .build();

            return client.responses()
                .create(params, requestOptions)
                .thenApply(response -> handleResponse(input, state, response))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        clear(input.requestId());
                    }
                });
        } catch (RuntimeException failure) {
            clear(input.requestId());
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public synchronized void clear(UUID requestId) {
        states.remove(Objects.requireNonNull(requestId, "requestId"));
    }

    @Override
    public void close() {
        synchronized (this) {
            states.clear();
        }
        if (ownsClient) {
            client.close();
        }
    }

    private synchronized RequestState stateFor(LunaTurnInput input) {
        RequestState existing = states.get(input.requestId());
        if (existing != null) {
            return existing;
        }

        List<ResponseInputItem> items = new ArrayList<>();
        items.add(
            ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                    .addInputTextContent(LunaPrompt.renderConversation(input))
                    .role(ResponseInputItem.Message.Role.USER)
                    .build()
            )
        );
        RequestState created = new RequestState(items);
        states.put(input.requestId(), created);
        return created;
    }

    private ResponseCreateParams createParams(
        LunaTurnInput input,
        DeterministicRoutePolicy.RoutingDecision routing,
        RequestState state
    ) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
            .model(LunaPrompt.MODEL)
            .instructions(LunaPrompt.instructions(routing))
            .input(ResponseCreateParams.Input.ofResponse(List.copyOf(state.inputItems)))
            .parallelToolCalls(true)
            .reasoning(
                Reasoning.builder()
                    .effort(ReasoningEffort.MEDIUM)
                    .build()
            )
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
            .store(false);

        for (LunaToolSchemas.Definition definition :
            toolSchemas.definitions(input.availableTools())) {
            builder.addTool(toFunctionTool(definition));
        }
        return builder.build();
    }

    private FunctionTool toFunctionTool(LunaToolSchemas.Definition definition) {
        FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
        for (Map.Entry<String, JsonElement> entry :
            definition.parameters().entrySet()) {
            parameters.putAdditionalProperty(
                entry.getKey(),
                JsonValue.from(toJavaValue(entry.getValue()))
            );
        }

        return FunctionTool.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(parameters.build())
            .strict(true)
            .build();
    }

    private LunaStep handleResponse(
        LunaTurnInput input,
        RequestState state,
        Response response
    ) {
        List<PendingCall> pending = new ArrayList<>();
        List<LunaStep.ToolCall> calls = new ArrayList<>();

        for (ResponseOutputItem item : response.output()) {
            if (item.isReasoning()) {
                state.inputItems.add(
                    ResponseInputItem.ofReasoning(item.asReasoning())
                );
                continue;
            }
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall functionCall = item.asFunctionCall();
                state.inputItems.add(
                    ResponseInputItem.ofFunctionCall(functionCall)
                );
                LunaStep.ToolCall translated = toolSchemas.translate(
                    functionCall.name(),
                    functionCall.arguments(),
                    input.availableTools()
                );
                pending.add(
                    new PendingCall(functionCall.callId(), translated.tool())
                );
                calls.add(translated);
                continue;
            }
            if (item.isMessage()) {
                state.inputItems.add(
                    ResponseInputItem.ofResponseOutputMessage(item.asMessage())
                );
                continue;
            }
            throw new IllegalStateException(
                "Luna returned an unsupported Responses output item."
            );
        }

        if (!calls.isEmpty()) {
            state.pendingCalls = List.copyOf(pending);
            return new LunaStep.Tools(calls);
        }

        String text = extractOutputText(response).trim();
        if (text.isEmpty()) {
            throw new IllegalStateException(
                "Luna returned neither text nor Tool calls."
            );
        }

        clear(input.requestId());
        return new LunaStep.Final(text, LunaStep.SessionState.CONTINUE);
    }

    private void appendFunctionOutputs(
        RequestState state,
        LunaTurnInput input
    ) {
        List<ConversationEntry.ToolMessage> currentToolEntries =
            input.history().stream()
                .filter(ConversationEntry.ToolMessage.class::isInstance)
                .map(ConversationEntry.ToolMessage.class::cast)
                .filter(entry -> entry.requestId().equals(input.requestId()))
                .toList();

        List<ConversationEntry.ToolMessage> newEntries =
            currentToolEntries.subList(
                Math.min(state.consumedToolEntries, currentToolEntries.size()),
                currentToolEntries.size()
            );

        if (
            newEntries.size() != state.pendingCalls.size()
                || !toolSequenceMatches(newEntries, state.pendingCalls)
        ) {
            throw new IllegalStateException(
                "Luna Tool-call/result sequence is inconsistent."
            );
        }

        for (int index = 0; index < newEntries.size(); index += 1) {
            ConversationEntry.ToolMessage entry = newEntries.get(index);
            PendingCall pending = state.pendingCalls.get(index);
            state.inputItems.add(
                ResponseInputItem.ofFunctionCallOutput(
                    ResponseInputItem.FunctionCallOutput.builder()
                        .callId(pending.callId())
                        .output(gson.toJson(entry.result()))
                        .build()
                )
            );
        }

        state.consumedToolEntries = currentToolEntries.size();
        state.pendingCalls = List.of();
    }

    private boolean toolSequenceMatches(
        List<ConversationEntry.ToolMessage> entries,
        List<PendingCall> pending
    ) {
        for (int index = 0; index < entries.size(); index += 1) {
            if (entries.get(index).tool() != pending.get(index).tool()) {
                return false;
            }
        }
        return true;
    }

    private String extractOutputText(Response response) {
        StringBuilder output = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> message.content().forEach(content ->
                content.outputText().ifPresent(text -> output.append(text.text()))
            ));
        }
        return output.toString();
    }

    private static OpenAIClientAsync createClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OPENAI_API_KEY must not be blank.");
        }
        return OpenAIOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .maxRetries(0)
            .timeout(MAX_TIMEOUT)
            .build();
    }

    private Object toJavaValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry :
                value.getAsJsonObject().entrySet()) {
                object.put(entry.getKey(), toJavaValue(entry.getValue()));
            }
            return object;
        }
        if (value.isJsonArray()) {
            List<Object> array = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> array.add(toJavaValue(item)));
            return array;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return value.getAsNumber();
        }
        return value.getAsString();
    }

    private static final class RequestState {
        private final List<ResponseInputItem> inputItems;
        private List<PendingCall> pendingCalls = List.of();
        private int consumedToolEntries;

        private RequestState(List<ResponseInputItem> inputItems) {
            this.inputItems = inputItems;
        }
    }

    private record PendingCall(String callId, ToolName tool) {
        private PendingCall {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(tool, "tool");
        }
    }

    private static final class InstantJsonAdapter implements JsonSerializer<Instant> {
        @Override
        public JsonElement serialize(
            Instant src,
            Type typeOfSrc,
            JsonSerializationContext context
        ) {
            return new com.google.gson.JsonPrimitive(src.toString());
        }
    }
}
