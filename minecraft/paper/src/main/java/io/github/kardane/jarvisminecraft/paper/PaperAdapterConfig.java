package io.github.kardane.jarvisminecraft.paper;

import io.github.kardane.jarvisminecraft.common.transport.SharedSecretAuthenticator;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

public record PaperAdapterConfig(
    String serverId,
    URI brainUri,
    String sharedSecret,
    long reconnectDelayTicks
) {
    private static final Pattern SERVER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "[::1]"
    );

    public static PaperAdapterConfig validate(
        String serverId,
        String brainUrl,
        String sharedSecret,
        long reconnectDelayTicks
    ) {
        if (serverId == null || !SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("server-id must match protocol serverId rules.");
        }

        URI uri;
        try {
            uri = URI.create(brainUrl);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("brain-url is invalid.", failure);
        }

        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("brain-url must use ws on loopback.");
        }
        String host = uri.getHost();
        if (host == null || !LOOPBACK_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("brain-url must use a loopback host.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("brain-url must not contain credentials, query, or fragment.");
        }

        SharedSecretAuthenticator.validateConfiguration(sharedSecret);

        if (reconnectDelayTicks < 20 || reconnectDelayTicks > 20 * 60) {
            throw new IllegalArgumentException("reconnect-delay-ticks must be between 20 and 1200.");
        }

        return new PaperAdapterConfig(
            serverId,
            uri,
            sharedSecret,
            reconnectDelayTicks
        );
    }
}
