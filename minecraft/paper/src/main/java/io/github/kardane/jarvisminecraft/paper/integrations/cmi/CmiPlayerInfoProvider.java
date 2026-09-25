package io.github.kardane.jarvisminecraft.paper.integrations.cmi;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.CmiPlayerInfoData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerRef;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import org.bukkit.ChatColor;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Read-only online-player CMI profile Tool. */
public final class CmiPlayerInfoProvider implements AutoCloseable {
    public static final String SOURCE = "CMI";
    private static final int MAX_NICKNAME_LENGTH = 64;

    private final ProfileLookup lookup;
    private final Clock clock;

    public CmiPlayerInfoProvider(ProfileLookup lookup, Clock clock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void registerTools(ToolRegistry registry) {
        registry.register(
            ToolName.GET_CMI_PLAYER_INFO,
            PlayerUuidArguments.class,
            (context, arguments) -> CompletableFuture.completedFuture(profile(arguments))
        );
    }

    ToolResult profile(PlayerUuidArguments arguments) {
        UUID playerUuid = arguments.playerUuid();
        LookupResult result;
        try {
            result = lookup.lookup(playerUuid);
        } catch (RuntimeException | LinkageError unavailable) {
            return unavailable();
        }
        if (result == null || result instanceof Unavailable) {
            return unavailable();
        }
        if (result instanceof Offline) {
            return ToolResult.error(
                ErrorCode.NOT_FOUND,
                "The player is not online.",
                false,
                clock.instant(),
                SOURCE
            );
        }

        Found found = (Found) result;
        Nickname nickname = nickname(found.nickname(), found.playerName());
        return new ToolResult(
            ResultStatus.OK,
            new CmiPlayerInfoData(
                new PlayerRef(playerUuid, found.playerName()),
                nickname.value(),
                found.afk()
            ),
            null,
            clock.instant(),
            SOURCE,
            nickname.truncated()
        );
    }

    private ToolResult unavailable() {
        return ToolResult.error(
            ErrorCode.PROVIDER_UNAVAILABLE,
            "CMI player information is currently unavailable.",
            true,
            clock.instant(),
            SOURCE
        );
    }

    private static Nickname nickname(String value, String fallback) {
        String input = value == null ? "" : value;
        String plain = ChatColor.stripColor(input);
        StringBuilder sanitized = new StringBuilder();
        boolean removedCharacters = !input.equals(plain);
        for (int offset = 0; offset < plain.length();) {
            int codePoint = plain.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                removedCharacters = true;
                continue;
            }
            if (sanitized.length() + Character.charCount(codePoint) > MAX_NICKNAME_LENGTH) {
                return new Nickname(sanitized.toString(), true);
            }
            sanitized.appendCodePoint(codePoint);
        }
        String candidate = sanitized.toString().strip();
        if (candidate.isEmpty()) {
            candidate = fallback;
        }
        return new Nickname(candidate, removedCharacters);
    }

    @Override
    public void close() {
        // No worker or external resource is owned by this provider.
    }

    @FunctionalInterface
    public interface ProfileLookup {
        LookupResult lookup(UUID playerUuid);
    }

    public sealed interface LookupResult permits Found, Offline, Unavailable {
    }

    public record Found(String playerName, String nickname, boolean afk) implements LookupResult {
    }

    public record Offline() implements LookupResult {
    }

    public record Unavailable() implements LookupResult {
    }

    private record Nickname(String value, boolean truncated) {
    }
}
