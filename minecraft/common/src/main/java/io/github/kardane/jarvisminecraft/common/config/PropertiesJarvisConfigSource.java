package io.github.kardane.jarvisminecraft.common.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

public final class PropertiesJarvisConfigSource implements JarvisConfigSource {
    private final Properties properties;

    private PropertiesJarvisConfigSource(Properties properties) {
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    public static PropertiesJarvisConfigSource load(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Properties properties = new Properties();
        if (!Files.exists(normalized)) {
            return new PropertiesJarvisConfigSource(properties);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(
                "Runtime configuration path is not a regular file."
            );
        }
        try (Reader reader = Files.newBufferedReader(
            normalized,
            StandardCharsets.UTF_8
        )) {
            properties.load(reader);
            return new PropertiesJarvisConfigSource(properties);
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not read runtime configuration file.",
                failure
            );
        }
    }

    public static PropertiesJarvisConfigSource from(Properties properties) {
        return new PropertiesJarvisConfigSource(properties);
    }

    @Override
    public Optional<String> string(String path) {
        return raw(path);
    }

    @Override
    public Optional<Boolean> bool(String path) {
        return raw(path).map(value -> {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "true" -> true;
                case "false" -> false;
                default -> throw invalidType(path, "boolean");
            };
        });
    }

    @Override
    public Optional<Integer> integer(String path) {
        return raw(path).map(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException failure) {
                throw invalidType(path, "integer");
            }
        });
    }

    @Override
    public Optional<Double> decimal(String path) {
        return raw(path).map(value -> {
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException failure) {
                throw invalidType(path, "number");
            }
        });
    }

    @Override
    public Optional<List<String>> stringList(String path) {
        return raw(path).map(value -> {
            if (value.isBlank()) {
                return List.of();
            }
            return Arrays.stream(value.split("\\|", -1))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
        });
    }

    private Optional<String> raw(String path) {
        return Optional.ofNullable(properties.getProperty(path));
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
