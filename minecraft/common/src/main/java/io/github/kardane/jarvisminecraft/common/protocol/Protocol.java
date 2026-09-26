package io.github.kardane.jarvisminecraft.common.protocol;

import com.google.gson.annotations.SerializedName;

import java.util.Arrays;

public final class Protocol {
    private Protocol() {
    }

    public enum ErrorCode {
        UNAUTHORIZED,
        INVALID_ARGUMENT,
        UNSUPPORTED,
        NOT_FOUND,
        AMBIGUOUS_TARGET,
        BUSY,
        TIMEOUT,
        PROVIDER_UNAVAILABLE,
        CANCELLED,
        OUTCOME_UNKNOWN,
        INTERNAL
    }

    public enum ResultStatus {
        OK,
        EMPTY,
        ERROR,
        UNSUPPORTED
    }

    public enum Risk {
        READ_ONLY,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum ToolName {
        @SerializedName("get_server_status")
        GET_SERVER_STATUS("get_server_status", "server.status", false, Risk.READ_ONLY),
        @SerializedName("get_online_players")
        GET_ONLINE_PLAYERS("get_online_players", "player.list", false, Risk.READ_ONLY),
        @SerializedName("get_player")
        GET_PLAYER("get_player", "player.lookup", false, Risk.READ_ONLY),
        @SerializedName("get_player_location")
        GET_PLAYER_LOCATION("get_player_location", "player.location", false, Risk.READ_ONLY),
        @SerializedName("get_nearby_players")
        GET_NEARBY_PLAYERS("get_nearby_players", "player.nearby", false, Risk.READ_ONLY),
        @SerializedName("get_world_info")
        GET_WORLD_INFO("get_world_info", "world.info", false, Risk.READ_ONLY),
        @SerializedName("teleport_staff")
        TELEPORT_STAFF("teleport_staff", "staff.self_teleport", true, Risk.LOW),
        @SerializedName("lookup_area_history")
        LOOKUP_AREA_HISTORY("lookup_area_history", "history.lookup", false, Risk.READ_ONLY),
        @SerializedName("lookup_player_history")
        LOOKUP_PLAYER_HISTORY("lookup_player_history", "history.lookup", false, Risk.READ_ONLY),
        @SerializedName("get_regions_at_location")
        GET_REGIONS_AT_LOCATION("get_regions_at_location", "region.lookup", false, Risk.READ_ONLY),
        @SerializedName("get_region_info")
        GET_REGION_INFO("get_region_info", "region.lookup", false, Risk.READ_ONLY),
        @SerializedName("check_build_permission")
        CHECK_BUILD_PERMISSION("check_build_permission", "region.protection", false, Risk.READ_ONLY),
        @SerializedName("get_cmi_player_info")
        GET_CMI_PLAYER_INFO("get_cmi_player_info", "player.cmi_profile", false, Risk.READ_ONLY);

        private final String wireName;
        private final String capability;
        private final boolean stateChanging;
        private final Risk risk;

        ToolName(String wireName, String capability, boolean stateChanging, Risk risk) {
            this.wireName = wireName;
            this.capability = capability;
            this.stateChanging = stateChanging;
            this.risk = risk;
        }

        public String wireName() {
            return wireName;
        }

        public String capability() {
            return capability;
        }

        public boolean stateChanging() {
            return stateChanging;
        }

        public Risk risk() {
            return risk;
        }

        public static ToolName fromWire(String value) {
            return Arrays.stream(values())
                .filter(v -> v.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new ProtocolException(ErrorCode.UNSUPPORTED, "Tool is not registered."));
        }
    }

    public enum BuildDecision {
        ALLOW,
        DENY,
        UNDEFINED
    }

    public enum CancelReason {
        CLIENT_DISCONNECTED,
        SESSION_ENDED,
        DEADLINE_EXCEEDED,
        OP_REVOKED,
        SHUTDOWN
    }

    public enum ActionState {
        EXECUTING,
        SUCCEEDED,
        FAILED,
        OUTCOME_UNKNOWN
    }
}
