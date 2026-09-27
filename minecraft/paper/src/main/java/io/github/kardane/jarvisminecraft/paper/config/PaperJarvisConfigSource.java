package io.github.kardane.jarvisminecraft.paper.config;

import io.github.kardane.jarvisminecraft.common.config.JarvisConfigSource;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PaperJarvisConfigSource implements JarvisConfigSource {
    private final ConfigurationSection config;

    public PaperJarvisConfigSource(ConfigurationSection config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<String> string(String path) {
        return raw(path).map(value -> {
            if (value instanceof String text) {
                return text;
            }
            throw invalidType(path, "string");
        });
    }

    @Override
    public Optional<Boolean> bool(String path) {
        return raw(path).map(value -> {
            if (value instanceof Boolean bool) {
                return bool;
            }
            throw invalidType(path, "boolean");
        });
    }

    @Override
    public Optional<Integer> integer(String path) {
        return raw(path).map(value -> {
            if (!(value instanceof Number number)) {
                throw invalidType(path, "integer");
            }
            double doubleValue = number.doubleValue();
            int intValue = number.intValue();
            if (
                !Double.isFinite(doubleValue)
                    || doubleValue != (double) intValue
            ) {
                throw invalidType(path, "integer");
            }
            return intValue;
        });
    }

    @Override
    public Optional<Double> decimal(String path) {
        return raw(path).map(value -> {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            throw invalidType(path, "number");
        });
    }

    @Override
    public Optional<List<String>> stringList(String path) {
        return raw(path).map(value -> {
            if (!(value instanceof List<?> values)) {
                throw invalidType(path, "string list");
            }
            List<String> result = new ArrayList<>(values.size());
            for (Object item : values) {
                if (!(item instanceof String text)) {
                    throw invalidType(path, "string list");
                }
                result.add(text);
            }
            return List.copyOf(result);
        });
    }

    private Optional<Object> raw(String path) {
        return Optional.ofNullable(config.get(path));
    }

    private IllegalArgumentException invalidType(
        String path,
        String expected
    ) {
        return new IllegalArgumentException(
            path + " must be a valid " + expected + "."
        );
    }
}
