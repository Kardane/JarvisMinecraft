package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

public interface JevClassifier {
    CompletionStage<JevClassification> classify(JevInput input, Instant deadlineAt);
}
