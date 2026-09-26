package io.github.kardane.jarvisminecraft.common.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuditMasker {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern OPENAI_KEY =
        Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER =
        Pattern.compile("(?i)Bearer\\s+[^\\s,;]+");

    private AuditMasker() {
    }

    public static Object sanitize(Object value) {
        return sanitize(value, null);
    }

    private static Object sanitize(Object value, String key) {
        if (key != null && sensitiveKey(key)) {
            return REDACTED;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                output.put(
                    childKey,
                    sanitize(entry.getValue(), childKey)
                );
            }
            return output;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> output = new ArrayList<>();
            for (Object item : iterable) {
                output.add(sanitize(item, null));
            }
            return output;
        }
        if (value instanceof String text) {
            return sanitizeString(text);
        }
        if (
            value instanceof Number
                || value instanceof Boolean
                || value instanceof UUID
                || value instanceof Instant
                || value instanceof Enum<?>
        ) {
            return value instanceof Number || value instanceof Boolean
                ? value
                : value.toString();
        }
        return sanitizeString(String.valueOf(value));
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
            || normalized.contains("password")
            || normalized.contains("authorization")
            || normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.endsWith("token")
            || normalized.contains("_token");
    }

    private static String sanitizeString(String value) {
        String masked = OPENAI_KEY.matcher(value).replaceAll(REDACTED);
        return BEARER.matcher(masked).replaceAll("Bearer " + REDACTED);
    }
}
