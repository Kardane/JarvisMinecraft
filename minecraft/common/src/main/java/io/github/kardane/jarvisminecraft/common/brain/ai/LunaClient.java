package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface LunaClient extends AutoCloseable {
    String modelId();

    CompletionStage<LunaStep> next(
        LunaTurnInput input,
        DeterministicRoutePolicy.RoutingDecision routing
    );

    void clear(UUID requestId);

    default void close() {
        // Implementations that own transport resources may override.
    }
}

