package io.github.kardane.jarvisminecraft.common.config;

import java.util.List;
import java.util.Optional;

public interface JarvisConfigSource {
    Optional<String> string(String path);

    Optional<Boolean> bool(String path);

    Optional<Integer> integer(String path);

    Optional<Double> decimal(String path);

    Optional<List<String>> stringList(String path);

    static JarvisConfigSource empty() {
        return new JarvisConfigSource() {
            @Override
            public Optional<String> string(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> bool(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> integer(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Double> decimal(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> stringList(String path) {
                return Optional.empty();
            }
        };
    }
}
