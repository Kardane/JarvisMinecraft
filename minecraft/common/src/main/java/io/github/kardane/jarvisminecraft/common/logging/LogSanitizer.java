package io.github.kardane.jarvisminecraft.common.logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class LogSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_VALUE_LENGTH = 512;
    private static final Pattern OPENAI_KEY = Pattern.compile(
        "(?i)sk-[a-z0-9_-]{8,}"
    );
    private static final Pattern BEARER = Pattern.compile(
        "(?i)bearer\\s+[^\\s,;]+"
    );

    private LogSanitizer() {
    }

    public static Map<String, String> sanitizeFields(
        Map<String, ?> fields
    ) {
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        if (fields == null) {
            return Map.of();
        }
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            String key = sanitizeKey(entry.getKey());
            sanitized.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Map.copyOf(sanitized);
    }

    public static String formatEvent(
        String event,
        Map<String, ?> fields
    ) {
        StringBuilder line = new StringBuilder("[JARVIS] ")
            .append(sanitizeEvent(event));
        sanitizeFields(fields).forEach((key, value) ->
            line.append(' ')
                .append(key)
                .append('=')
                .append(value)
        );
        return line.toString();
    }

    private static String sanitizeEvent(String event) {
        if (event == null || event.isBlank()) {
            return "unknown";
        }
        return clean(event, 128);
    }

    private static String sanitizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "field";
        }
        return clean(key, 128);
    }

    private static String sanitizeValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value == null) {
            return "null";
        }
        String text = clean(String.valueOf(value), MAX_VALUE_LENGTH);
        text = OPENAI_KEY.matcher(text).replaceAll(REDACTED);
        text = BEARER.matcher(text).replaceAll("Bearer " + REDACTED);
        return text;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT)
            .replace("-", "")
            .replace("_", "");
        return normalized.contains("apikey")
            || normalized.contains("authorization")
            || normalized.contains("password")
            || normalized.contains("credential")
            || normalized.endsWith("secret")
            || normalized.endsWith("token");
    }

    private static String clean(String value, int maxLength) {
        String normalized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
