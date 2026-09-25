package io.github.kardane.jarvisminecraft.paper.integrations.worldguard;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionInfoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionInfoData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionsAtLocationArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionsAtLocationData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class T12VerificationMain {
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-000000001201");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-000000001202");
    private static final UUID REGION_OWNER = UUID.fromString("00000000-0000-4000-8000-000000001203");
    private static final Location LOCATION = new Location("world", 20, 70, -4, 0, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);

    private T12VerificationMain() {
    }

    public static void main(String[] args) {
        dependencyContract();
        regionPriorityParentAndMemberContract();
        buildPermissionAndBypassContract();
        emptyAndMissingContract();
        failureAndRegistrationContract();
        System.out.println("T12 verification OK");
    }

    private static void dependencyContract() {
        require(!WorldGuardDependencyCheck.isAvailable(false, false), "missing WorldGuard/WorldEdit was accepted");
        require(!WorldGuardDependencyCheck.isAvailable(true, false), "missing WorldEdit was accepted");
        require(!WorldGuardDependencyCheck.isAvailable(false, true), "missing WorldGuard was accepted");
        require(WorldGuardDependencyCheck.isAvailable(true, true), "enabled WorldGuard plus WorldEdit was rejected");
    }

    private static void regionPriorityParentAndMemberContract() {
        FakeAccess access = new FakeAccess();
        WorldGuardRegionProvider provider = new WorldGuardRegionProvider(access, CLOCK);
        ToolResult at = provider.regionsAt(new RegionsAtLocationArguments(LOCATION));
        RegionsAtLocationData summaries = (RegionsAtLocationData) at.data();
        require(at.status() == Protocol.ResultStatus.OK, "applicable region query should succeed");
        require(summaries.regions().size() == 2, "priority-ordered regions were lost");
        require(summaries.regions().get(0).priority() == 20 && summaries.regions().get(1).priority() == 4,
            "WorldGuard priority order/value was not preserved");
        require(summaries.regions().get(0).owners().contains(REGION_OWNER.toString()), "region owner UUID missing");
        require(summaries.regions().get(0).members().contains("Builder"), "region member name missing");
        require(summaries.regions().get(0).members().contains("group:staff"), "region member group missing");

        ToolResult info = provider.regionInfo(new RegionInfoArguments("world", "spawn-child"));
        RegionInfoData detail = (RegionInfoData) info.data();
        require("spawn".equals(detail.parentId()), "WorldGuard parent relationship was lost");
        require(detail.priority() == 20, "region detail priority mismatch");
        require("DENY".equals(detail.flags().get("build")), "WorldGuard state flag was not serialized as a scalar");
        require(Boolean.FALSE.equals(detail.flags().get("pvp")), "Boolean flag type should be preserved");
        require(Double.valueOf(1.5).equals(detail.flags().get("time-rate")), "numeric flag type should be preserved");
    }

    private static void buildPermissionAndBypassContract() {
        FakeAccess access = new FakeAccess();
        WorldGuardRegionProvider provider = new WorldGuardRegionProvider(access, CLOCK);

        access.permission = new WorldGuardRegionProvider.PermissionView(
            Protocol.BuildDecision.UNDEFINED,
            true,
            List.of("spawn-child", "spawn"),
            "bypass active",
            false
        );
        ToolResult bypassResult = provider.checkBuild(new BuildPermissionArguments(ADMIN, LOCATION));
        BuildPermissionData bypass = (BuildPermissionData) bypassResult.data();
        require(bypass.bypass(), "WorldGuard bypass state missing");
        require(bypass.decision() == Protocol.BuildDecision.ALLOW, "active bypass must permit the evaluated build");
        require(bypass.matchedRegions().equals(List.of("spawn-child", "spawn")), "matched region list mismatch");

        access.permission = new WorldGuardRegionProvider.PermissionView(
            Protocol.BuildDecision.DENY,
            false,
            List.of("spawn-child"),
            "build flag denies",
            false
        );
        BuildPermissionData denied = (BuildPermissionData) provider.checkBuild(
            new BuildPermissionArguments(MEMBER, LOCATION)
        ).data();
        require(denied.decision() == Protocol.BuildDecision.DENY && !denied.bypass(),
            "non-bypass DENY must remain unchanged");
    }

    private static void emptyAndMissingContract() {
        FakeAccess access = new FakeAccess();
        WorldGuardRegionProvider provider = new WorldGuardRegionProvider(access, CLOCK);
        access.regions = Optional.of(List.of());
        ToolResult empty = provider.regionsAt(new RegionsAtLocationArguments(LOCATION));
        require(empty.status() == Protocol.ResultStatus.EMPTY
            && ((RegionsAtLocationData) empty.data()).regions().isEmpty(), "no applicable regions must be an explicit empty result");

        access.regions = Optional.empty();
        ToolResult missingWorld = provider.regionsAt(new RegionsAtLocationArguments(LOCATION));
        require(missingWorld.error() != null && missingWorld.error().code() == Protocol.ErrorCode.NOT_FOUND,
            "unloaded world must not be reported as an empty region set");
        ToolResult missingRegion = provider.regionInfo(new RegionInfoArguments("world", "missing"));
        require(missingRegion.error() != null && missingRegion.error().code() == Protocol.ErrorCode.NOT_FOUND,
            "missing region must be reported explicitly");
    }

    private static void failureAndRegistrationContract() {
        FakeAccess absentWorldEdit = new FakeAccess();
        absentWorldEdit.worldEditEnabled = false;
        ToolResult unavailable = new WorldGuardRegionProvider(absentWorldEdit, CLOCK)
            .regionsAt(new RegionsAtLocationArguments(LOCATION));
        require(unavailable.error() != null && unavailable.error().code() == Protocol.ErrorCode.PROVIDER_UNAVAILABLE,
            "WorldEdit absence must disable WorldGuard Tools");

        FakeAccess failing = new FakeAccess();
        failing.fail = true;
        ToolResult failed = new WorldGuardRegionProvider(failing, CLOCK)
            .regionsAt(new RegionsAtLocationArguments(LOCATION));
        require(failed.error() != null && failed.error().code() == Protocol.ErrorCode.PROVIDER_UNAVAILABLE,
            "query failures must fail closed");

        ToolRegistry registry = new ToolRegistry();
        new WorldGuardRegionProvider(new FakeAccess(), CLOCK).registerTools(registry);
        require(registry.contains(Protocol.ToolName.GET_REGIONS_AT_LOCATION), "region lookup Tool not registered by Provider");
        require(registry.contains(Protocol.ToolName.GET_REGION_INFO), "region info Tool not registered by Provider");
        require(registry.contains(Protocol.ToolName.CHECK_BUILD_PERMISSION), "build permission Tool not registered by Provider");
    }

    private static WorldGuardRegionProvider.RegionView region(
        String id,
        int priority,
        String parentId,
        List<String> owners,
        List<String> members,
        Map<String, Object> flags
    ) {
        return new WorldGuardRegionProvider.RegionView(id, priority, parentId, owners, members, flags, false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeAccess implements WorldGuardRegionProvider.Access {
        private boolean worldGuardEnabled = true;
        private boolean worldEditEnabled = true;
        private boolean fail;
        private Optional<List<WorldGuardRegionProvider.RegionView>> regions = Optional.of(List.of(
            region("spawn-child", 20, "spawn", List.of(REGION_OWNER.toString()),
                List.of("Builder", "group:staff"), Map.of(
                    "build", "DENY",
                    "pvp", false,
                    "time-rate", 1.5
                )),
            region("spawn", 4, null, List.of(), List.of(), Map.of())
        ));
        private WorldGuardRegionProvider.RegionView detail = region(
            "spawn-child",
            20,
            "spawn",
            List.of(REGION_OWNER.toString()),
            List.of("Builder", "group:staff"),
            Map.of("build", "DENY", "pvp", false, "time-rate", 1.5)
        );
        private WorldGuardRegionProvider.PermissionView permission = new WorldGuardRegionProvider.PermissionView(
            Protocol.BuildDecision.DENY,
            false,
            List.of("spawn-child"),
            "build flag denies",
            false
        );

        @Override
        public boolean worldGuardEnabled() {
            return worldGuardEnabled;
        }

        @Override
        public boolean worldEditEnabled() {
            return worldEditEnabled;
        }

        @Override
        public Optional<List<WorldGuardRegionProvider.RegionView>> regionsAt(Location location) {
            if (fail) {
                throw new IllegalStateException("fake query failure");
            }
            return regions;
        }

        @Override
        public Optional<WorldGuardRegionProvider.RegionView> regionInfo(String worldId, String regionId) {
            if (fail) {
                throw new IllegalStateException("fake query failure");
            }
            return "spawn-child".equals(regionId) ? Optional.of(detail) : Optional.empty();
        }

        @Override
        public Optional<WorldGuardRegionProvider.PermissionView> checkBuild(UUID playerUuid, Location location) {
            if (fail) {
                throw new IllegalStateException("fake query failure");
            }
            return Optional.of(permission);
        }
    }
}
