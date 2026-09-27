package io.github.kardane.jarvisminecraft.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JarvisFields {
    private JarvisFields() {
    }

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException(
                "JarvisFields requires key/value pairs."
            );
        }

        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object rawKey = keyValues[index];
            if (!(rawKey instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException(
                    "JarvisFields keys must be non-blank strings."
                );
            }
            fields.put(key, keyValues[index + 1]);
        }
        return Map.copyOf(fields);
    }
}
