package io.github.kardane.jarvisminecraft.paper.integrations.cmi;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.CmiPlayerInfoData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

public final class T14VerificationMain {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000001401");

    private T14VerificationMain() {
    }

    public static void main(String[] args) {
        verifiesToolRegistration();
        verifiesOnlineProfileAndSanitization();
        verifiesNicknameFallbackAndLimit();
        verifiesOfflineAndUnavailableResults();
        verifiesLinkageFailureFailsClosed();
        System.out.println("T14 verification OK");
    }

    private static void verifiesToolRegistration() {
        ToolRegistry registry = new ToolRegistry();
        CmiPlayerInfoProvider provider = new CmiPlayerInfoProvider(
            uuid -> new CmiPlayerInfoProvider.Offline(),
            CLOCK
        );
        provider.registerTools(registry);
        require(registry.contains(ToolName.GET_CMI_PLAYER_INFO), "CMI profile Tool was not registered");
        provider.close();
    }

    private static void verifiesOnlineProfileAndSanitization() {
        CmiPlayerInfoProvider provider = provider(
            uuid -> new CmiPlayerInfoProvider.Found("Alex", "§aMaster\u202eAlex", true)
        );
        ToolResult result = provider.profile(new PlayerUuidArguments(PLAYER));
        require(result.status() == ResultStatus.OK, "online CMI profile should succeed");
        require(CmiPlayerInfoProvider.SOURCE.equals(result.source()), "CMI source marker missing");
        require(result.observedAt().equals(CLOCK.instant()), "observation time should use injected clock");
        CmiPlayerInfoData data = (CmiPlayerInfoData) result.data();
        require(data.player().uuid().equals(PLAYER), "player UUID mismatch");
        require("Alex".equals(data.player().name()), "Minecraft player name mismatch");
        require("MasterAlex".equals(data.nickname()), "nickname colors or format controls were not removed");
        require(data.afk(), "CMI AFK state mismatch");
        require(result.truncated(), "sanitized untrusted nickname must be marked as transformed");
    }

    private static void verifiesNicknameFallbackAndLimit() {
        ToolResult fallbackResult = provider(
            uuid -> new CmiPlayerInfoProvider.Found("Alex", null, false)
        ).profile(new PlayerUuidArguments(PLAYER));
        CmiPlayerInfoData fallback = (CmiPlayerInfoData) fallbackResult.data();
        require("Alex".equals(fallback.nickname()), "empty CMI nickname should fall back to player name");

        ToolResult limitedResult = provider(
            uuid -> new CmiPlayerInfoProvider.Found("Alex", "N".repeat(80), false)
        ).profile(new PlayerUuidArguments(PLAYER));
        CmiPlayerInfoData limited = (CmiPlayerInfoData) limitedResult.data();
        require(limited.nickname().length() == 64, "CMI nickname must be capped at 64 UTF-16 units");
        require(limitedResult.truncated(), "capped nickname must report truncation");
    }

    private static void verifiesOfflineAndUnavailableResults() {
        ToolResult offline = provider(uuid -> new CmiPlayerInfoProvider.Offline())
            .profile(new PlayerUuidArguments(PLAYER));
        require(offline.status() == ResultStatus.ERROR, "offline player should be an error");
        require(offline.error().code() == ErrorCode.NOT_FOUND, "offline player error code mismatch");

        ToolResult unavailable = provider(uuid -> new CmiPlayerInfoProvider.Unavailable())
            .profile(new PlayerUuidArguments(PLAYER));
        require(unavailable.status() == ResultStatus.ERROR, "missing CMI user should fail closed");
        require(unavailable.error().code() == ErrorCode.PROVIDER_UNAVAILABLE, "provider error code mismatch");
    }

    private static void verifiesLinkageFailureFailsClosed() {
        ToolResult result = provider(uuid -> {
            throw new NoSuchMethodError("simulated runtime API mismatch");
        }).profile(new PlayerUuidArguments(PLAYER));
        require(result.status() == ResultStatus.ERROR, "runtime API mismatch should fail closed");
        require(result.error().code() == ErrorCode.PROVIDER_UNAVAILABLE, "linkage failure error code mismatch");
    }

    private static CmiPlayerInfoProvider provider(CmiPlayerInfoProvider.ProfileLookup lookup) {
        return new CmiPlayerInfoProvider(lookup, CLOCK);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
