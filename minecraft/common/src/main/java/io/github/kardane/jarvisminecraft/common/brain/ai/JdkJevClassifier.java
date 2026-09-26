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
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(3);
    public static final URI DEFAULT_ENDPOINT =
        URI.create("https://api.typesafe.ai/v1/systemone");

    private static final Map<JevCategory, String> ROUTES = Map.of(
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
        JevCategory.ACTION_REQUEST,
        "The operator explicitly asks JARVIS to perform a supported server-side action.",
        JevCategory.GENERAL,
        "General conversation that does not require Minecraft server data or an action.",
        JevCategory.UNCERTAIN,
        "The intent is ambiguous or does not safely fit another route."
    );

    private static final String INSTRUCTIONS =
        "Classify this Minecraft server-operator JARVIS request into exactly one route. "
            + "Choose ACTION_REQUEST only when the operator explicitly asks JARVIS "
            + "to change server state.";

    private final String apiKey;
    private final URI endpoint;
    private final HttpClient http;
    private final Clock clock;
    private final Gson gson = new Gson();

    public JdkJevClassifier(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    public JdkJevClassifier(
        String apiKey,
        URI endpoint,
        HttpClient http,
        Clock clock
    ) {
        this.apiKey = requireSecret(apiKey);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
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

        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        Duration timeout = remaining.compareTo(MAX_TIMEOUT) < 0 ? remaining : MAX_TIMEOUT;
        if (timeout.isZero() || timeout.isNegative()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Jev request deadline has expired.")
            );
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(input)))
            .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(this::parseResponse);
    }

    private String buildBody(JevInput input) {
        JsonObject state = new JsonObject();
        state.addProperty("latest_message", clip(input.latestMessage(), 4_096));
        state.addProperty("short_topic", clip(input.shortTopic(), 2_000));
        state.add(
            "capabilities",
            gson.toJsonTree(input.capabilities().stream().limit(64).toList())
        );

        JsonObject criteria = new JsonObject();
        for (JevCategory category : JevCategory.values()) {
            criteria.addProperty(category.name(), ROUTES.get(category));
        }

        JsonObject route = new JsonObject();
        route.addProperty("type", "choice");
        route.addProperty("instructions", INSTRUCTIONS);
        route.add("criteria", criteria);

        JsonObject questions = new JsonObject();
        questions.add("route", route);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("state", state);
        body.add("questions", questions);
        return gson.toJson(body);
    }

    private JevClassification parseResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Jev request failed with HTTP " + response.statusCode() + "."
            );
        }

        JsonObject root = requireObject(JsonParser.parseString(response.body()), "response");
        String model = requireString(root, "model");
        if (!MODEL.equals(model)) {
            throw new IllegalStateException("Jev model id did not match the pinned model.");
        }

        JsonObject answers = requireObject(root.get("answers"), "answers");
        JsonObject route = requireObject(answers.get("route"), "answers.route");
        if (!"choice".equals(requireString(route, "type"))) {
            throw new IllegalStateException("Jev route answer was not a choice.");
        }

        final JevCategory category;
        try {
            category = JevCategory.valueOf(requireString(route, "choice"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Jev returned an unknown route.", failure);
        }

        double confidence = requireProbability(route, "confidence");
        JsonObject probabilityObject =
            requireObject(route.get("probabilities"), "answers.route.probabilities");
        EnumMap<JevCategory, Double> probabilities = new EnumMap<>(JevCategory.class);
        for (Map.Entry<String, JsonElement> entry : probabilityObject.entrySet()) {
            final JevCategory key;
            try {
                key = JevCategory.valueOf(entry.getKey());
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException(
                    "Jev returned an unknown route probability.",
                    failure
                );
            }
            probabilities.put(key, probability(entry.getValue(), entry.getKey()));
        }

        String requestId = response.headers()
            .firstValue("x-typesafe-request-id")
            .orElse(null);

        return new JevClassification(
            category,
            confidence,
            probabilities,
            model,
            requestId
        );
    }

    private JsonObject requireObject(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException(field + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
        ) {
            throw new IllegalStateException(field + " must be a string.");
        }
        return element.getAsString();
    }

    private double requireProbability(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return probability(element, field);
    }

    private double probability(JsonElement element, String field) {
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()
        ) {
            throw new IllegalStateException(field + " must be numeric.");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalStateException(field + " must be between 0 and 1.");
        }
        return value;
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String requireSecret(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("TYPESAFE_API_KEY must not be blank.");
        }
        return value;
    }
}
