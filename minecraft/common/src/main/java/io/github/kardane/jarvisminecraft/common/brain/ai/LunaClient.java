package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface LunaClient {
    String modelId();

    CompletionStage<LunaStep> next(
        LunaTurnInput input,
        DeterministicRoutePolicy.RoutingDecision routing
    );

    void clear(UUID requestId);
}
