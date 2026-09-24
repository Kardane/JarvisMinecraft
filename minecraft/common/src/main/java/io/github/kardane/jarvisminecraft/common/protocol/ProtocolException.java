package io.github.kardane.jarvisminecraft.common.protocol;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class ProtocolException extends RuntimeException {
    private final ErrorCode code;

    public ProtocolException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ProtocolException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
