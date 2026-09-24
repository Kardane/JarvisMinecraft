package io.github.kardane.jarvisminecraft.common.transport;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JdkBrainWebSocketTransport implements BrainTransport {
    private final URI uri;
    private final String secret;
    private final HttpClient httpClient;
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public JdkBrainWebSocketTransport(URI uri, String secret) {
        this(
            uri,
            secret,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
        );
    }

    JdkBrainWebSocketTransport(URI uri, String secret, HttpClient httpClient) {
        this.uri = Objects.requireNonNull(uri, "uri");
        SharedSecretAuthenticator.validateConfiguration(secret);
        this.secret = secret;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public CompletionStage<Void> connect(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed."));
        }
        if (socket.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is already connected."));
        }

        ListenerAdapter adapter = new ListenerAdapter(listener);
        return httpClient.newWebSocketBuilder()
            .header(Protocol.SECRET_HEADER, secret)
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(uri, adapter)
            .thenAccept(ws -> {
                socket.set(ws);
                listener.onConnected();
            });
    }

    @Override
    public CompletionStage<Void> send(String message) {
        Objects.requireNonNull(message, "message");
        if (message.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_MESSAGE_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message exceeds protocol limit."));
        }
        WebSocket current = socket.get();
        if (current == null || current.isOutputClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is not connected."));
        }
        return current.sendText(message, true).thenApply(ignored -> null);
    }

    @Override
    public boolean connected() {
        WebSocket current = socket.get();
        return current != null && !current.isInputClosed() && !current.isOutputClosed();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket current = socket.getAndSet(null);
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    private final class ListenerAdapter implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder fragments = new StringBuilder();
        private int utf8Bytes;

        private ListenerAdapter(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String chunk = data.toString();
            utf8Bytes += chunk.getBytes(StandardCharsets.UTF_8).length;
            if (utf8Bytes > Protocol.MAX_MESSAGE_BYTES) {
                webSocket.sendClose(1009, "message too large");
                socket.compareAndSet(webSocket, null);
                listener.onFailure(new IllegalArgumentException("Inbound message exceeds protocol limit."));
                return CompletableFuture.completedFuture(null);
            }

            fragments.append(chunk);
            if (last) {
                String message = fragments.toString();
                fragments.setLength(0);
                utf8Bytes = 0;
                listener.onMessage(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket.compareAndSet(webSocket, null);
            listener.onDisconnected(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket.compareAndSet(webSocket, null);
            listener.onFailure(error);
        }
    }
}
