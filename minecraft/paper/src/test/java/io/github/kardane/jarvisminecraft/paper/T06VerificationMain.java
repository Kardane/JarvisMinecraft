package io.github.kardane.jarvisminecraft.paper;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.GetPlayerByNameArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NearbyArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.OnlinePlayersData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PagingArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerLocationData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ServerStatusData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WorldInfoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WorldInfoData;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.LocationSnapshot;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.NearbyPlayerSnapshot;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.PlayerSnapshot;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.ServerStatusSnapshot;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.TeleportSnapshot;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess.WorldSnapshot;
import io.github.kardane.jarvisminecraft.paper.tools.PaperToolService;
import io.github.kardane.jarvisminecraft.paper.transport.PaperBrainConnection;
import io.github.kardane.jarvisminecraft.common.runtime.RequestBindingRegistry;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class T06VerificationMain {
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-000000000601");
    private static final UUID STEVE = UUID.fromString("00000000-0000-4000-8000-000000000602");
    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-000000000603");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-8000-000000000604");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-4000-8000-000000000605");

    private T06VerificationMain() {
    }

    public static void main(String[] args) {
        chatContract();
        configContract();
        requestBindingContract();
        toolContract();
        System.out.println("T06 verification OK");
    }

    private static void chatContract() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
        ChatSessionManager sessions = new ChatSessionManager(clock);

        var nonOp = sessions.accept(ADMIN, false, "자비스 서버 상태");
        require(nonOp.kind() == ChatSessionManager.Kind.PUBLIC_CHAT, "non-OP invocation must remain public");

        var partial = sessions.accept(ADMIN, true, "자비스팅 서버 상태");
        require(partial.kind() == ChatSessionManager.Kind.PUBLIC_CHAT, "partial alias must not invoke JARVIS");

        var direct = sessions.accept(ADMIN, true, "JarVis 서버 상태 어때?");
        require(direct.kind() == ChatSessionManager.Kind.FORWARD, "direct alias not forwarded");
        require("DIRECT".equals(direct.mode()), "direct mode mismatch");
        require(direct.started(), "direct invocation must start session");

        UUID sessionId = direct.sessionId();
        var follow = sessions.accept(ADMIN, true, "그럼 접속자는?");
        require(follow.kind() == ChatSessionManager.Kind.FORWARD, "follow-up not forwarded");
        require("FOLLOW_UP".equals(follow.mode()), "follow-up mode mismatch");
        require(sessionId.equals(follow.sessionId()), "session id changed during follow-up");

        var escaped = sessions.accept(ADMIN, true, "!이건 공개 채팅");
        require(escaped.kind() == ChatSessionManager.Kind.PUBLIC_ESCAPE, "public escape not recognized");
        require("이건 공개 채팅".equals(escaped.text()), "public escape marker not removed");

        var ended = sessions.accept(ADMIN, true, "대화 끝");
        require(ended.kind() == ChatSessionManager.Kind.END, "local end not recognized");
        require(!sessions.isActive(ADMIN, sessionId), "ended session remained active");

        var directAgain = sessions.accept(ADMIN, true, "재비스 다시 시작");
        clock.advanceMillis(ChatSessionManager.TTL_MILLIS + 1);
        require(!sessions.isActive(ADMIN, directAgain.sessionId()), "expired session remained active");

        var directThird = sessions.accept(ADMIN, true, "자비스 세 번째");
        clock.advanceMillis(ChatSessionManager.TTL_MILLIS + 1);
        require(
            sessions.pruneExpired().stream().anyMatch(item -> item.sessionId().equals(directThird.sessionId())),
            "periodic expiry sweep did not return expired session"
        );
    }

    private static void configContract() {
        PaperAdapterConfig valid = PaperAdapterConfig.validate(
            "main",
            "ws://127.0.0.1:8181/ws",
            "correct-horse-battery-staple",
            40
        );
        require("main".equals(valid.serverId()), "valid config server id mismatch");

        expectFailure(() -> PaperAdapterConfig.validate(
            "main",
            "ws://example.com:8181/ws",
            "correct-horse-battery-staple",
            40
        ));
        expectFailure(() -> PaperAdapterConfig.validate(
            "main",
            "ws://127.0.0.1:8181/ws",
            "CHANGE_ME",
            40
        ));
        expectFailure(() -> PaperAdapterConfig.validate(
            "main",
            "ws://127.0.0.1:8181/ws?secret=no",
            "correct-horse-battery-staple",
            40
        ));
    }

    private static void requestBindingContract() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
        RequestBindingRegistry registry = new RequestBindingRegistry(clock);
        registry.register(REQUEST, ADMIN, SESSION, clock.instant().plusSeconds(30));

        ProtocolMessage valid = boundMessage(REQUEST, ADMIN, SESSION);
        require(registry.matches(valid), "valid actor/request/session binding rejected");

        require(
            !registry.matches(boundMessage(REQUEST, STEVE, SESSION)),
            "mismatched requester binding accepted"
        );
        require(
            !registry.matches(boundMessage(REQUEST, ADMIN, UUID.randomUUID())),
            "mismatched session binding accepted"
        );

        clock.advanceMillis(30_001);
        require(!registry.matches(valid), "expired request binding accepted");
    }

    private static void toolContract() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
        FakePlatform platform = new FakePlatform();
        platform.operator = true;

        ToolRegistry registry = new ToolRegistry();
        new PaperToolService(platform, clock).register(registry);
        require(registry.tools().equals(PaperBrainConnection.V01_TOOLS), "v0.1 Tool registry mismatch");

        ToolRegistry.ToolExecutionContext context = new ToolRegistry.ToolExecutionContext(
            "main",
            UUID.randomUUID(),
            ADMIN,
            REQUEST,
            SESSION,
            UUID.randomUUID(),
            null,
            clock.instant().plusSeconds(5)
        );

        ToolResult status = execute(
            registry,
            ToolName.GET_SERVER_STATUS,
            context,
            new io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments()
        );
        require(status.status() == ResultStatus.OK, "server status failed");
        ServerStatusData statusData = (ServerStatusData) status.data();
        require(statusData.tps().value() == 19.9, "TPS snapshot mismatch");
        require("tps".equals(statusData.tps().unit()), "TPS unit missing");

        ToolResult page = execute(
            registry,
            ToolName.GET_ONLINE_PLAYERS,
            context,
            new PagingArguments(null, 2)
        );
        OnlinePlayersData pageData = (OnlinePlayersData) page.data();
        require(pageData.players().size() == 2, "online pagination size mismatch");
        require(pageData.nextCursor() != null, "online pagination cursor missing");
        require(page.truncated(), "online pagination truncation flag missing");

        ToolResult player = execute(
            registry,
            ToolName.GET_PLAYER,
            context,
            new GetPlayerByNameArguments("Steve")
        );
        require(player.status() == ResultStatus.OK, "exact player lookup failed");

        ToolResult location = execute(
            registry,
            ToolName.GET_PLAYER_LOCATION,
            context,
            new PlayerUuidArguments(STEVE)
        );
        PlayerLocationData locationData = (PlayerLocationData) location.data();
        require("world".equals(locationData.location().worldId()), "player location world mismatch");

        ToolResult nearby = execute(
            registry,
            ToolName.GET_NEARBY_PLAYERS,
            context,
            new NearbyArguments(
                new io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location(
                    "world", 0, 64, 0, 0, 0
                ),
                64,
                10
            )
        );
        require(nearby.status() == ResultStatus.OK, "nearby players failed");

        ToolResult world = execute(
            registry,
            ToolName.GET_WORLD_INFO,
            context,
            new WorldInfoArguments("world")
        );
        WorldInfoData worldData = (WorldInfoData) world.data();
        require("minecraft:overworld".equals(worldData.dimensionKey()), "world dimension mismatch");

        ToolRegistry.ToolExecutionContext actionContext = new ToolRegistry.ToolExecutionContext(
            "main",
            UUID.randomUUID(),
            ADMIN,
            REQUEST,
            SESSION,
            UUID.randomUUID(),
            UUID.randomUUID(),
            clock.instant().plusSeconds(5)
        );
        ToolResult teleport = execute(
            registry,
            ToolName.TELEPORT_STAFF,
            actionContext,
            new TeleportArguments(STEVE)
        );
        require(teleport.status() == ResultStatus.OK, "self teleport failed");
        TeleportData teleportData = (TeleportData) teleport.data();
        require(ADMIN.equals(teleportData.requesterUuid()), "teleport moved a non-requester");
        require(STEVE.equals(teleportData.targetPlayerUuid()), "teleport target mismatch");
        require(ADMIN.equals(platform.lastTeleportRequester), "platform was asked to move the wrong player");

        platform.operator = false;
        ToolResult deopped = execute(
            registry,
            ToolName.TELEPORT_STAFF,
            actionContext,
            new TeleportArguments(STEVE)
        );
        require(deopped.status() == ResultStatus.ERROR, "deopped requester teleport was not blocked");
    }

    private static ToolResult execute(
        ToolRegistry registry,
        ToolName tool,
        ToolRegistry.ToolExecutionContext context,
        io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments arguments
    ) {
        return registry.execute(tool, context, arguments).toCompletableFuture().join();
    }

    private static ProtocolMessage boundMessage(UUID requestId, UUID requester, UUID session) {
        return new ProtocolMessage(
            Protocol.VERSION,
            Protocol.MessageType.CHAT_RESPONSE,
            UUID.randomUUID(),
            requestId,
            "main",
            session,
            requester,
            Instant.parse("2026-09-24T00:00:01Z"),
            Instant.parse("2026-09-24T00:00:30Z"),
            new ProtocolMessage.ChatResponse("ok", true, "CONTINUE"),
            null,
            null
        );
    }

    private static void expectFailure(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakePlatform implements PaperPlatformAccess {
        boolean operator;
        UUID lastTeleportRequester;

        private final List<PlayerSnapshot> players = List.of(
            player(ADMIN, "Admin", 0, 64, 0),
            player(STEVE, "Steve", 10, 64, 0),
            player(ALEX, "Alex", 20, 64, 0)
        );

        @Override
        public boolean isServerThread() {
            return true;
        }

        @Override
        public boolean isOnlineOperator(UUID playerUuid) {
            return ADMIN.equals(playerUuid) && operator;
        }

        @Override
        public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
            return players.stream().filter(player -> player.uuid().equals(playerUuid)).findFirst();
        }

        @Override
        public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
            return players.stream().filter(player -> player.name().equals(exactName)).findFirst();
        }

        @Override
        public List<PlayerSnapshot> onlinePlayers() {
            return players;
        }

        @Override
        public Optional<WorldSnapshot> findLoadedWorld(String worldId) {
            if (!"world".equals(worldId)) {
                return Optional.empty();
            }
            return Optional.of(new WorldSnapshot("world", "minecraft:overworld", 3, "NORMAL", 6000));
        }

        @Override
        public ServerStatusSnapshot serverStatus() {
            return new ServerStatusSnapshot(19.9, 12.5, 3, 42, 1024, 4096);
        }

        @Override
        public List<NearbyPlayerSnapshot> nearbyPlayers(LocationSnapshot center, double radius, int limit) {
            List<NearbyPlayerSnapshot> result = new ArrayList<>();
            for (PlayerSnapshot player : players) {
                double dx = player.location().x() - center.x();
                double dy = player.location().y() - center.y();
                double dz = player.location().z() - center.z();
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance <= radius) {
                    result.add(new NearbyPlayerSnapshot(player, distance));
                }
            }
            return result.stream().limit(limit).toList();
        }

        @Override
        public CompletionStage<TeleportSnapshot> teleportRequesterTo(UUID requesterUuid, UUID targetPlayerUuid) {
            lastTeleportRequester = requesterUuid;
            return CompletableFuture.completedFuture(
                new TeleportSnapshot(requesterUuid, targetPlayerUuid, "world", "world", true)
            );
        }

        @Override
        public void sendPrivatePlain(UUID requesterUuid, String text) {
            throw new AssertionError("not used by fake Tool tests");
        }

        private static PlayerSnapshot player(UUID uuid, String name, double x, double y, double z) {
            return new PlayerSnapshot(
                uuid,
                name,
                true,
                new LocationSnapshot("world", x, y, z, 0, 0)
            );
        }
    }
}
