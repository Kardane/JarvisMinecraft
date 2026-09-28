package io.github.kardane.jarvisminecraft.neoforge.platform;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.chat.StyledChatMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WeatherType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftNeoForgePlatformAccess implements NeoForgePlatformAccess {
    private static final String MESSAGE_PREFIX = "[JARVIS] ";

    private final MinecraftServer server;
    private final NeoForgeTickSampler tickSampler;

    public MinecraftNeoForgePlatformAccess(
        MinecraftServer server,
        NeoForgeTickSampler tickSampler
    ) {
        this.server = server;
        this.tickSampler = tickSampler;
    }

    @Override
    public boolean isServerThread() {
        return server.isSameThread();
    }

    @Override
    public boolean isOnlineOperator(UUID playerUuid) {
        requireServerThread();
        PlayerList manager = server.getPlayerList();
        ServerPlayer player = manager.getPlayer(playerUuid);
        return player != null && manager.isOp(player.getGameProfile());
    }

    @Override
    public Optional<PlayerIdentity> interactionPlayer(UUID playerUuid) {
        requireServerThread();
        PlayerList manager = server.getPlayerList();
        ServerPlayer player = manager.getPlayer(playerUuid);
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(
            new PlayerIdentity(
                playerUuid,
                player.getGameProfile().getName(),
                true,
                manager.isOp(player.getGameProfile())
            )
        );
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
        requireServerThread();
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        return player == null ? Optional.empty() : Optional.of(snapshot(player));
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
        requireServerThread();
        ServerPlayer player = server.getPlayerList().getPlayerByName(exactName);
        if (player == null || !player.getGameProfile().getName().equals(exactName)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(player));
    }

    @Override
    public List<PlayerSnapshot> onlinePlayers() {
        requireServerThread();
        return server.getPlayerList()
            .getPlayers()
            .stream()
            .map(this::snapshot)
            .sorted(Comparator.comparing(snapshot -> snapshot.uuid().toString()))
            .toList();
    }

    @Override
    public Optional<WorldSnapshot> findLoadedWorld(String worldId) {
        requireServerThread();
        return findWorld(worldId).map(this::worldSnapshot);
    }

    @Override
    public ServerStatusSnapshot serverStatus() {
        requireServerThread();

        double mspt = tickSampler.averageMspt();
        double tpsEstimate = tickSampler.estimatedTps();

        int loadedChunks = 0;
        for (ServerLevel world : server.getAllLevels()) {
            loadedChunks += world.getChunkSource().getLoadedChunksCount();
        }

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();

        return new ServerStatusSnapshot(
            tpsEstimate,
            mspt,
            server.getPlayerList().getPlayers().size(),
            loadedChunks,
            used,
            runtime.maxMemory()
        );
    }

    @Override
    public List<NearbyPlayerSnapshot> nearbyPlayers(
        LocationSnapshot center,
        double radius,
        int limit
    ) {
        requireServerThread();
        Optional<ServerLevel> found = findWorld(center.worldId());
        if (found.isEmpty()) {
            return List.of();
        }

        double maxDistanceSquared = radius * radius;
        List<NearbyPlayerSnapshot> output = new ArrayList<>();

        for (ServerPlayer player : found.get().players()) {
            double dx = player.getX() - center.x();
            double dy = player.getY() - center.y();
            double dz = player.getZ() - center.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= maxDistanceSquared) {
                output.add(
                    new NearbyPlayerSnapshot(
                        snapshot(player),
                        Math.sqrt(distanceSquared)
                    )
                );
            }
        }

        output.sort(
            Comparator
                .comparingDouble(NearbyPlayerSnapshot::distance)
                .thenComparing(item -> item.player().uuid().toString())
        );
        if (output.size() > limit) {
            return List.copyOf(output.subList(0, limit));
        }
        return List.copyOf(output);
    }

    @Override
    public CompletionStage<TeleportSnapshot> teleportRequesterTo(
        UUID requesterUuid,
        UUID targetPlayerUuid
    ) {
        requireServerThread();
        PlayerList manager = server.getPlayerList();
        ServerPlayer requester = manager.getPlayer(requesterUuid);
        ServerPlayer target = manager.getPlayer(targetPlayerUuid);

        if (
            requester == null
                || target == null
                || !manager.isOp(requester.getGameProfile())
        ) {
            return CompletableFuture.completedFuture(
                new TeleportSnapshot(
                    requesterUuid,
                    targetPlayerUuid,
                    "",
                    "",
                    false
                )
            );
        }

        String fromWorld = worldId(serverLevel(requester));
        String toWorld = worldId(serverLevel(target));
        boolean completed = requester.teleportTo(
            serverLevel(target),
            target.getX(),
            target.getY(),
            target.getZ(),
            Set.of(),
            target.getYRot(),
            target.getXRot(),
            true
        );

        return CompletableFuture.completedFuture(
            new TeleportSnapshot(
                requesterUuid,
                targetPlayerUuid,
                fromWorld,
                toWorld,
                completed
            )
        );
    }

    @Override
    public Set<String> commandRoots() {
        requireServerThread();
        return server.getCommands()
            .getDispatcher()
            .getRoot()
            .getChildren()
            .stream()
            .map(node -> node.getName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public CommandExecutionSnapshot executeConsoleCommand(
        String command
    ) {
        requireServerThread();
        try {
            int result = server.getCommands()
                .getDispatcher()
                .execute(
                    command,
                    server.createCommandSourceStack()
                );
            return new CommandExecutionSnapshot(
                result,
                true
            );
        } catch (CommandSyntaxException failure) {
            return new CommandExecutionSnapshot(
                0,
                false
            );
        }
    }

    @Override
    public Optional<WeatherMutationSnapshot> setWeather(
        String worldId,
        WeatherType weather,
        int durationSeconds
    ) {
        requireServerThread();
        Optional<ServerLevel> found = findWorld(worldId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ServerLevel world = found.get();
        int durationTicks = Math.multiplyExact(
            durationSeconds,
            20
        );
        switch (weather) {
            case CLEAR ->
                world.setWeatherParameters(
                    durationTicks,
                    0,
                    false,
                    false
                );
            case RAIN ->
                world.setWeatherParameters(
                    0,
                    durationTicks,
                    true,
                    false
                );
            case THUNDER ->
                world.setWeatherParameters(
                    0,
                    durationTicks,
                    true,
                    true
                );
        }

        return Optional.of(
            new WeatherMutationSnapshot(
                worldId(world),
                weather,
                durationSeconds,
                true
            )
        );
    }

    @Override
    public Optional<TimeMutationSnapshot> setTimeOfDay(
        String worldId,
        int timeOfDay
    ) {
        requireServerThread();
        Optional<ServerLevel> found = findWorld(worldId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ServerLevel world = found.get();
        long current = world.getDayTime();
        long dayBase =
            current - Math.floorMod(current, 24_000L);
        world.setDayTime(dayBase + timeOfDay);

        return Optional.of(
            new TimeMutationSnapshot(
                worldId(world),
                (int) Math.floorMod(
                    world.getDayTime(),
                    24_000L
                ),
                true
            )
        );
    }

    @Override
    public void sendPrivatePlain(UUID requesterUuid, String text) {
        requireServerThread();
        PlayerList manager = server.getPlayerList();
        ServerPlayer player = manager.getPlayer(requesterUuid);
        if (player == null || !manager.isOp(player.getGameProfile())) {
            return;
        }
        player.sendSystemMessage(Component.literal(MESSAGE_PREFIX + text), false);
    }

    @Override
    public void sendPublicPlain(String text) {
        requireServerThread();
        Component message = Component.literal(MESSAGE_PREFIX + text);
        server.getPlayerList().getPlayers().forEach(
            player -> player.sendSystemMessage(message, false)
        );
    }

    @Override
    public void sendPublicStyled(StyledChatMessage message) {
        requireServerThread();
        Component rendered = renderStyled(message);
        server.getPlayerList().getPlayers().forEach(
            player -> player.sendSystemMessage(rendered, false)
        );
    }

    @Override
    public void playResponseSound(
        UUID requesterUuid,
        String soundId,
        float volume,
        float pitch
    ) {
        requireServerThread();
        ServerPlayer player =
            server.getPlayerList().getPlayer(requesterUuid);
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (player == null || id == null) {
            return;
        }
        BuiltInRegistries.SOUND_EVENT.getOptional(id).ifPresent(
            sound -> player.playNotifySound(
                sound,
                SoundSource.MASTER,
                volume,
                pitch
            )
        );
    }

    private Component renderStyled(StyledChatMessage message) {
        MutableComponent output = Component.empty();
        for (StyledChatMessage.Segment segment : message.prefix()) {
            Style style = Style.EMPTY;
            if (segment.rgb() != null) {
                style = style.withColor(segment.rgb());
            }
            style = style
                .withObfuscated(segment.obfuscated())
                .withBold(segment.bold())
                .withStrikethrough(segment.strikethrough())
                .withUnderlined(segment.underlined())
                .withItalic(segment.italic());
            output.append(
                Component.literal(segment.text()).setStyle(style)
            );
        }
        for (StyledChatMessage.Segment segment : message.bodySegments()) {
            Style style = Style.EMPTY;
            if (segment.rgb() != null) {
                style = style.withColor(segment.rgb());
            }
            style = style
                .withObfuscated(segment.obfuscated())
                .withBold(segment.bold())
                .withStrikethrough(segment.strikethrough())
                .withUnderlined(segment.underlined())
                .withItalic(segment.italic());
            output.append(
                Component.literal(segment.text()).setStyle(style)
            );
        }
        for (StyledChatMessage.HoverSegment segment : message.suffix()) {
            MutableComponent suffix = Component.empty();
            for (StyledChatMessage.Segment part : segment.segments()) {
                Style style = Style.EMPTY;
                if (part.rgb() != null) {
                    style = style.withColor(part.rgb());
                }
                style = style
                    .withObfuscated(part.obfuscated())
                    .withBold(part.bold())
                    .withStrikethrough(part.strikethrough())
                    .withUnderlined(part.underlined())
                    .withItalic(part.italic());
                suffix.append(
                    Component.literal(part.text()).setStyle(style)
                );
            }
            suffix.setStyle(
                suffix.getStyle().withHoverEvent(
                    new HoverEvent.ShowText(
                        Component.literal(segment.hoverText())
                    )
                )
            );
            output.append(suffix);
        }
        return output;
    }

    private Optional<ServerLevel> findWorld(String worldId) {
        for (ServerLevel world : server.getAllLevels()) {
            String canonical = worldId(world);
            if (
                canonical.equals(worldId)
                    || world.dimension().location().getPath().equals(worldId)
            ) {
                return Optional.of(world);
            }
        }
        return Optional.empty();
    }

    private WorldSnapshot worldSnapshot(ServerLevel world) {
        return new WorldSnapshot(
            worldId(world),
            world.dimensionTypeRegistration().getRegisteredName(),
            world.players().size(),
            world.getDifficulty().getKey(),
            world.getDayTime()
        );
    }

    private PlayerSnapshot snapshot(ServerPlayer player) {
        return new PlayerSnapshot(
            player.getUUID(),
            player.getGameProfile().getName(),
            true,
            new LocationSnapshot(
                worldId(serverLevel(player)),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
            )
        );
    }

    private ServerLevel serverLevel(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            return level;
        }
        throw new IllegalStateException("Server player is not attached to a ServerLevel.");
    }

    private String worldId(ServerLevel world) {
        return world.dimension().location().toString();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("NeoForge server API accessed off the server thread.");
        }
    }
}
