package io.github.kardane.jarvisminecraft.common.brain.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class JdkJevClassifier implements JevClassifier {
    public static final String MODEL = "jev-1.13.0";
    public static final Duration MAX_TIMEOUT =
        Duration.ofSeconds(3);
    public static final URI DEFAULT_ENDPOINT =
        URI.create("https://api.typesafe.ai/v1/systemone");

    private static final Map<JevCategory, String> ROUTES =
        Map.of(
            JevCategory.SERVER_QUERY,
            "Current server health, TPS/MSPT, player count, or server-wide status.",
            JevCategory.PLAYER_QUERY,
            "A player lookup, online state, exact identity, position, or nearby players.",
            JevCategory.WORLD_QUERY,
            "Loaded world/dimension information or location/world context.",
            JevCategory.HISTORY_QUERY,
            "Historical block/player activity that requires a history provider.",
            JevCategory.REGION_QUERY,
            "Protected region, region flags, membership, ownership, or build protection.",
            JevCategory.WEB_QUERY,
            "Current or external information that requires searching the public web, such as recent Minecraft, Paper, plugin, outage, documentation, or other up-to-date facts.",
            JevCategory.ACTION_REQUEST,
            "The user explicitly asks JARVIS to perform a supported server-side action.",
            JevCategory.GENERAL,
            "General conversation that does not require Minecraft server data or an action.",
            JevCategory.UNCERTAIN,
            "The intent is ambiguous or does not safely fit another route."
        );

    private static final Map<JevEngagement, String> ENGAGEMENTS =
        Map.of(
            JevEngagement.IGNORE,
            "Use for proactive ambient chat that does not need JARVIS, or for FOLLOW_UP_CANDIDATE messages that are ordinary chat and not a continuation of the active JARVIS conversation.",
            JevEngagement.RESPOND,
            "Use for DIRECT/FOLLOW_UP requests. For FOLLOW_UP_CANDIDATE, choose only when the latest message is clearly a reply, clarification, continuation, or closely related question to the recent JARVIS conversation in short_topic.",
            JevEngagement.START_CONVERSATION,
            "Only for a PROACTIVE_CANDIDATE message where JARVIS should initiate a new conversation."
        );

    private static final Map<ReasoningLevel, String> REASONING =
        Map.of(
            ReasoningLevel.NONE,
            "A trivial greeting or very simple conversational response with no Tool use.",
            ReasoningLevel.LOW,
            "A direct, simple request or one straightforward lookup.",
            ReasoningLevel.MEDIUM,
            "A request needing multiple context clues, ordinary Tool use, or moderate synthesis.",
            ReasoningLevel.HIGH,
            "A complex multi-step analysis, ambiguous investigation, or several dependent results."
        );

    private static final String ROUTE_INSTRUCTIONS =
        "Classify this Minecraft JARVIS request into exactly one route. "
            + "Choose ACTION_REQUEST only when the user explicitly asks JARVIS "
            + "to change server state.";

    private static final String ENGAGEMENT_INSTRUCTIONS =
        "Decide whether JARVIS should engage with the latest message. "
            + "If interaction_origin is DIRECT, FOLLOW_UP, or PROACTIVE, choose RESPOND. "
            + "If interaction_origin is FOLLOW_UP_CANDIDATE, compare latest_message with short_topic and choose RESPOND only when it is clearly continuing or replying to that JARVIS conversation; unrelated ordinary chat must be IGNORE. "
            + "For PROACTIVE_CANDIDATE choose START_CONVERSATION only when a useful, context-aware JARVIS intervention is warranted; otherwise choose IGNORE.";

    private static final String REASONING_INSTRUCTIONS =
        "Choose the minimum reasoning effort that is sufficient for a safe, "
            + "correct response. Tool availability does not grant permission.";

    private final String apiKey;
    private final URI endpoint;
    private final HttpClient http;
    private final Clock clock;
    private final Gson gson = new Gson();

    public JdkJevClassifier(String apiKey) {
        this(
            apiKey,
            DEFAULT_ENDPOINT,
            HttpClient.newHttpClient(),
            Clock.systemUTC()
        );
    }

    public JdkJevClassifier(
        String apiKey,
        URI endpoint,
        HttpClient http,
        Clock clock
    ) {
        this.apiKey = requireSecret(apiKey);
        this.endpoint = Objects.requireNonNull(
            endpoint,
            "endpoint"
        );
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<JevClassification> classify(
        JevInput input,
        Instant deadlineAt
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(deadlineAt, "deadlineAt");

        Duration remaining = Duration.between(
            clock.instant(),
            deadlineAt
        );
        Duration timeout =
            remaining.compareTo(MAX_TIMEOUT) < 0
                ? remaining
                : MAX_TIMEOUT;
        if (timeout.isZero() || timeout.isNegative()) {
            return java.util.concurrent.CompletableFuture
                .failedFuture(
                    new IllegalStateException(
                        "Jev request deadline has expired."
                    )
                );
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    buildBody(input)
                )
            )
            .build();

        return http.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        ).thenApply(this::parseResponse);
    }

    private String buildBody(JevInput input) {
        JsonObject state = new JsonObject();
        state.addProperty(
            "latest_message",
            clip(input.latestMessage(), 4_096)
        );
        state.addProperty(
            "short_topic",
            clip(input.shortTopic(), 2_000)
        );
        state.addProperty(
            "interaction_origin",
            clip(input.interactionOrigin(), 64)
        );
        state.add(
            "capabilities",
            gson.toJsonTree(
                input.capabilities()
                    .stream()
                    .limit(64)
                    .toList()
            )
        );

        JsonObject questions = new JsonObject();
        questions.add(
            "engagement",
            choiceQuestion(
                ENGAGEMENT_INSTRUCTIONS,
                ENGAGEMENTS
            )
        );
        questions.add(
            "route",
            choiceQuestion(
                ROUTE_INSTRUCTIONS,
                ROUTES
            )
        );
        questions.add(
            "reasoning",
            choiceQuestion(
                REASONING_INSTRUCTIONS,
                REASONING
            )
        );

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("state", state);
        body.add("questions", questions);
        return gson.toJson(body);
    }

    private <E extends Enum<E>> JsonObject choiceQuestion(
        String instructions,
        Map<E, String> criteriaMap
    ) {
        JsonObject criteria = new JsonObject();
        for (Map.Entry<E, String> entry :
            criteriaMap.entrySet()) {
            criteria.addProperty(
                entry.getKey().name(),
                entry.getValue()
            );
        }

        JsonObject question = new JsonObject();
        question.addProperty("type", "choice");
        question.addProperty(
            "instructions",
            instructions
        );
        question.add("criteria", criteria);
        return question;
    }

    private JevClassification parseResponse(
        HttpResponse<String> response
    ) {
        if (
            response.statusCode() < 200
                || response.statusCode() >= 300
        ) {
            throw new IllegalStateException(
                "Jev request failed with HTTP "
                    + response.statusCode()
                    + "."
            );
        }

        JsonObject root = requireObject(
            JsonParser.parseString(response.body()),
            "response"
        );
        String model = requireString(root, "model");
        if (!MODEL.equals(model)) {
            throw new IllegalStateException(
                "Jev model id did not match the pinned model."
            );
        }

        JsonObject answers = requireObject(
            root.get("answers"),
            "answers"
        );

        Choice<JevEngagement> engagement = parseChoice(
            answers,
            "engagement",
            JevEngagement.class
        );
        Choice<JevCategory> route = parseChoice(
            answers,
            "route",
            JevCategory.class
        );
        Choice<ReasoningLevel> reasoning = parseChoice(
            answers,
            "reasoning",
            ReasoningLevel.class
        );

        JsonObject probabilityObject = requireObject(
            requireObject(
                answers.get("route"),
                "answers.route"
            ).get("probabilities"),
            "answers.route.probabilities"
        );
        EnumMap<JevCategory, Double> probabilities =
            new EnumMap<>(JevCategory.class);
        for (
            Map.Entry<String, JsonElement> entry
                : probabilityObject.entrySet()
        ) {
            final JevCategory key;
            try {
                key = JevCategory.valueOf(
                    entry.getKey()
                );
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException(
                    "Jev returned an unknown route probability.",
                    failure
                );
            }
            probabilities.put(
                key,
                probability(
                    entry.getValue(),
                    entry.getKey()
                )
            );
        }

        String requestId = response.headers()
            .firstValue("x-typesafe-request-id")
            .orElse(null);

        return new JevClassification(
            route.value(),
            route.confidence(),
            probabilities,
            engagement.value(),
            engagement.confidence(),
            reasoning.value(),
            reasoning.confidence(),
            model,
            requestId
        );
    }

    private <E extends Enum<E>> Choice<E> parseChoice(
        JsonObject answers,
        String name,
        Class<E> enumType
    ) {
        JsonObject answer = requireObject(
            answers.get(name),
            "answers." + name
        );
        if (!"choice".equals(requireString(answer, "type"))) {
            throw new IllegalStateException(
                "Jev " + name + " answer was not a choice."
            );
        }

        final E value;
        try {
            value = Enum.valueOf(
                enumType,
                requireString(answer, "choice")
            );
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                "Jev returned an unknown " + name + " choice.",
                failure
            );
        }

        return new Choice<>(
            value,
            requireProbability(answer, "confidence")
        );
    }

    private JsonObject requireObject(
        JsonElement element,
        String field
    ) {
        if (
            element == null
                || !element.isJsonObject()
        ) {
            throw new IllegalStateException(
                field + " must be an object."
            );
        }
        return element.getAsJsonObject();
    }

    private String requireString(
        JsonObject object,
        String field
    ) {
        JsonElement element = object.get(field);
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element
                    .getAsJsonPrimitive()
                    .isString()
        ) {
            throw new IllegalStateException(
                field + " must be a string."
            );
        }
        return element.getAsString();
    }

    private double requireProbability(
        JsonObject object,
        String field
    ) {
        return probability(
            object.get(field),
            field
        );
    }

    private double probability(
        JsonElement element,
        String field
    ) {
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element
                    .getAsJsonPrimitive()
                    .isNumber()
        ) {
            throw new IllegalStateException(
                field + " must be numeric."
            );
        }
        double value = element.getAsDouble();
        if (
            !Double.isFinite(value)
                || value < 0.0
                || value > 1.0
        ) {
            throw new IllegalStateException(
                field + " must be between 0 and 1."
            );
        }
        return value;
    }

    private static String clip(
        String value,
        int maxLength
    ) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }

    private static String requireSecret(String value) {
        if (
            value == null
                || value.trim().isEmpty()
        ) {
            throw new IllegalArgumentException(
                "TYPESAFE_API_KEY must not be blank."
            );
        }
        return value;
    }

    private record Choice<E>(
        E value,
        double confidence
    ) {
        private Choice {
            Objects.requireNonNull(value, "value");
        }
    }
}
