package io.github.kardane.jarvisminecraft.common.generated;

/**
 * Committed contract constants retained after the E14 Node Brain removal.
 * Source of truth: protocol/schema/protocol.schema.json and config/v0.1-policy.json.
 * Update this file in the same change when either source changes.
 */
public final class GeneratedContractConstants {
    public static final String PROTOCOL_VERSION = "1.0";
    public static final int MAX_MESSAGE_BYTES = 65_536;
    public static final int MAX_TOOL_CALLS_PER_REQUEST = 8;
    public static final int MAX_MODEL_ROUND_TRIPS_PER_REQUEST = 4;
    public static final long SESSION_TTL_MILLIS = 120_000L;
    public static final long REQUEST_DEADLINE_MILLIS = 30_000L;

    private GeneratedContractConstants() {
    }
}
