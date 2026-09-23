package io.github.kardane.jarvisminecraft.common.transport;

import java.util.concurrent.CompletionStage;

public interface BrainTransport extends AutoCloseable {
    CompletionStage<Void> connect(Listener listener);

    CompletionStage<Void> send(String message);

    boolean connected();

    @Override
    void close();

    interface Listener {
        void onConnected();

        void onMessage(String message);

        void onDisconnected(int statusCode, String reason);

        void onFailure(Throwable failure);
    }
}
