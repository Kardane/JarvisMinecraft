package io.github.kardane.jarvisminecraft.paper.integrations;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.AreaHistoryArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerHistoryArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionInfoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionsAtLocationArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class T13VerificationMain {
    private static final IntegrationRegistry.ProviderModule COREPROTECT = new IntegrationRegistry.ProviderModule(
        "CoreProtect",
        "CoreProtect",
        Set.of("CoreProtect"),
        Set.of("history.lookup"),
        "test.CoreProtectModule"
    );
    private static final IntegrationRegistry.ProviderModule WORLDGUARD = new IntegrationRegistry.ProviderModule(
        "WorldGuard",
        "WorldGuard",
        Set.of("WorldGuard", "WorldEdit"),
        Set.of("region.lookup", "region.protection"),
        "test.WorldGuardModule"
    );
    private static final IntegrationRegistry.ProviderModule CMI = new IntegrationRegistry.ProviderModule(
        "CMI",
        "CMI",
        Set.of("CMI", "CMILib"),
        Set.of("player.cmi_profile"),
        "test.CmiModule"
    );

    private T13VerificationMain() {
    }

    public static void main(String[] args) {
        verifiesNoOptionalPlugins();
        verifiesCoreProtectOnly();
        verifiesWorldGuardRequiresWorldEdit();
        verifiesCmiRequiresCmiLib();
        verifiesCmiCapabilitiesUseProviderVersion();
        verifiesAllSupportedPlugins();
        verifiesDisabledAndBrokenProvidersFailClosed();
        verifiesProviderRegistrationIsAtomic();
        verifiesProviderShutdown();
        System.out.println("T13 verification OK");
    }

    private static void verifiesNoOptionalPlugins() {
        ToolRegistry tools = baseRegistry();
        IntegrationRegistry assembled = assemble(tools, Set.of(), Set.of(), fakeLoader(new AtomicInteger(), new AtomicInteger()), new ArrayList<>());
        require(tools.contains(ToolName.GET_SERVER_STATUS), "base Paper Tools must remain registered");
        require(providerTools(tools).isEmpty(), "optional Tools must be absent when plugins are absent");
        require(assembled.providerStates().stream().allMatch(
            state -> state.availability() == IntegrationRegistry.ProviderAvailability.ABSENT
        ), "absent optional plugins must be reported as absent");
    }

    private static void verifiesCoreProtectOnly() {
        ToolRegistry tools = baseRegistry();
        AtomicInteger coreLoads = new AtomicInteger();
        AtomicInteger worldGuardLoads = new AtomicInteger();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CoreProtect"),
            Set.of("COREPROTECT"),
            fakeLoader(coreLoads, worldGuardLoads),
            new ArrayList<>()
        );
        require(coreLoads.get() == 1 && worldGuardLoads.get() == 0, "only the installed provider should load");
        require(tools.contains(ToolName.LOOKUP_AREA_HISTORY), "CoreProtect area history Tool missing");
        require(tools.contains(ToolName.LOOKUP_PLAYER_HISTORY), "CoreProtect player history Tool missing");
        require(!tools.contains(ToolName.GET_REGIONS_AT_LOCATION), "WorldGuard Tools leaked without WorldGuard");
        require(assembled.providerStates().stream().anyMatch(state ->
            state.provider().equals("CoreProtect")
                && state.availability() == IntegrationRegistry.ProviderAvailability.ACTIVE
        ), "CoreProtect provider must be active");
    }

    private static void verifiesWorldGuardRequiresWorldEdit() {
        ToolRegistry tools = baseRegistry();
        AtomicInteger coreLoads = new AtomicInteger();
        AtomicInteger worldGuardLoads = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("WorldGuard", "WorldEdit"),
            Set.of("WorldGuard"),
            fakeLoader(coreLoads, worldGuardLoads),
            warnings
        );
        require(worldGuardLoads.get() == 0, "WorldGuard module loaded without enabled WorldEdit");
        require(providerTools(tools).isEmpty(), "WorldGuard Tools must stay hidden without WorldEdit");
        require(warnings.size() == 1, "missing required companion should be reported once");
        require(assembled.providerStates().stream().anyMatch(state ->
            state.provider().equals("WorldGuard")
                && state.availability() == IntegrationRegistry.ProviderAvailability.DEPENDENCY_MISSING
        ), "missing WorldEdit should be reported");
    }

    private static void verifiesCmiRequiresCmiLib() {
        ToolRegistry tools = baseRegistry();
        AtomicInteger cmiLoads = new AtomicInteger();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CMI", "CMILib"),
            Set.of("CMI"),
            (module, registry, server, clock) -> {
                cmiLoads.incrementAndGet();
                return () -> { };
            },
            new ArrayList<>()
        );
        require(cmiLoads.get() == 0, "CMI module loaded without enabled CMILib");
        require(!tools.contains(ToolName.GET_CMI_PLAYER_INFO), "CMI Tool leaked without CMILib");
        require(assembled.providerStates().stream().anyMatch(state ->
            state.provider().equals("CMI")
                && state.availability() == IntegrationRegistry.ProviderAvailability.DEPENDENCY_MISSING
        ), "missing CMILib should be reported");
    }

    private static void verifiesCmiCapabilitiesUseProviderVersion() {
        ToolRegistry tools = baseRegistry();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CMI", "CMILib"),
            Set.of("CMI", "CMILib"),
            fakeLoader(new AtomicInteger(), new AtomicInteger()),
            new ArrayList<>()
        );
        require(tools.contains(ToolName.GET_CMI_PLAYER_INFO), "CMI profile Tool missing");
        var cmiCapability = assembled.capabilities("1.21.8").stream()
            .filter(capability -> capability.name().equals("player.cmi_profile"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("CMI capability missing"));
        require("CMI".equals(cmiCapability.source()), "CMI capability source mismatch");
        require("9.8.9.6".equals(cmiCapability.version()), "CMI plugin version was not advertised");
    }

    private static void verifiesAllSupportedPlugins() {
        ToolRegistry tools = baseRegistry();
        AtomicInteger coreLoads = new AtomicInteger();
        AtomicInteger worldGuardLoads = new AtomicInteger();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CoreProtect", "WorldGuard", "WorldEdit", "CMI", "CMILib"),
            Set.of("CoreProtect", "WorldGuard", "WorldEdit", "CMI", "CMILib"),
            fakeLoader(coreLoads, worldGuardLoads),
            new ArrayList<>()
        );
        require(coreLoads.get() == 1 && worldGuardLoads.get() == 1, "both compatible providers should load");
        require(providerTools(tools).size() == 6, "the supported provider combination should expose six Tools");
        require(assembled.providerStates().stream().allMatch(
            state -> state.availability() == IntegrationRegistry.ProviderAvailability.ACTIVE
        ), "all installed providers should be active");
    }

    private static void verifiesDisabledAndBrokenProvidersFailClosed() {
        ToolRegistry disabledTools = baseRegistry();
        AtomicInteger disabledLoads = new AtomicInteger();
        IntegrationRegistry disabled = assemble(
            disabledTools,
            Set.of("CoreProtect"),
            Set.of(),
            (module, registry, server, clock) -> {
                disabledLoads.incrementAndGet();
                return null;
            },
            new ArrayList<>()
        );
        require(disabledLoads.get() == 0, "disabled plugin must not be loaded");
        require(providerTools(disabledTools).isEmpty(), "disabled plugin Tools must stay hidden");
        require(disabled.providerStates().getFirst().availability()
            == IntegrationRegistry.ProviderAvailability.DISABLED, "disabled plugin state missing");

        ToolRegistry brokenTools = baseRegistry();
        List<String> warnings = new ArrayList<>();
        IntegrationRegistry broken = assemble(
            brokenTools,
            Set.of("CoreProtect"),
            Set.of("CoreProtect"),
            (module, registry, server, clock) -> {
                register(registry, ToolName.LOOKUP_AREA_HISTORY, AreaHistoryArguments.class);
                throw new NoClassDefFoundError("simulated incompatible API");
            },
            warnings
        );
        require(providerTools(brokenTools).isEmpty(), "broken API must not expose provider Tools");
        require(broken.providerStates().getFirst().availability()
            == IntegrationRegistry.ProviderAvailability.API_UNAVAILABLE, "broken API state missing");
        require(warnings.size() == 1, "broken API should be reported once");
    }

    private static void verifiesProviderShutdown() {
        ToolRegistry tools = baseRegistry();
        AtomicInteger closed = new AtomicInteger();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CoreProtect"),
            Set.of("CoreProtect"),
            (module, registry, server, clock) -> {
                registerCoreProtectTools(registry);
                return closed::incrementAndGet;
            },
            new ArrayList<>()
        );
        assembled.close();
        assembled.close();
        require(closed.get() == 1, "provider resources must close exactly once");
    }

    private static void verifiesProviderRegistrationIsAtomic() {
        ToolRegistry tools = baseRegistry();
        register(tools, ToolName.LOOKUP_PLAYER_HISTORY, PlayerHistoryArguments.class);
        Set<ToolName> before = tools.tools();
        AtomicInteger closed = new AtomicInteger();
        IntegrationRegistry assembled = assemble(
            tools,
            Set.of("CoreProtect"),
            Set.of("CoreProtect"),
            (module, staged, server, clock) -> {
                registerCoreProtectTools(staged);
                return closed::incrementAndGet;
            },
            new ArrayList<>()
        );
        require(tools.tools().equals(before), "conflicting provider registration must expose no partial Tools");
        require(closed.get() == 1, "a provider rejected during registration must be closed");
        require(assembled.providerStates().getFirst().availability()
            == IntegrationRegistry.ProviderAvailability.API_UNAVAILABLE, "failed provider registration state missing");
    }

    private static ToolRegistry baseRegistry() {
        ToolRegistry registry = new ToolRegistry();
        register(registry, ToolName.GET_SERVER_STATUS, NoArguments.class);
        return registry;
    }

    private static IntegrationRegistry assemble(
        ToolRegistry registry,
        Set<String> installed,
        Set<String> enabled,
        IntegrationRegistry.ModuleLoader loader,
        List<String> warnings
    ) {
        return IntegrationRegistry.assemble(
            registry,
            installed,
            enabled,
            Map.of(
                "CoreProtect", "24.1",
                "WorldGuard", "7.0.18",
                "WorldEdit", "7.3.16",
                "CMI", "9.8.9.6",
                "CMILib", "1.5.9.9"
            ),
            List.of(COREPROTECT, WORLDGUARD, CMI),
            null,
            Clock.systemUTC(),
            loader,
            warnings::add
        );
    }

    private static IntegrationRegistry.ModuleLoader fakeLoader(
        AtomicInteger coreLoads,
        AtomicInteger worldGuardLoads
    ) {
        return (module, registry, server, clock) -> {
            if (module == COREPROTECT) {
                coreLoads.incrementAndGet();
                registerCoreProtectTools(registry);
            } else if (module == WORLDGUARD) {
                worldGuardLoads.incrementAndGet();
                registerWorldGuardTools(registry);
            } else if (module == CMI) {
                register(registry, ToolName.GET_CMI_PLAYER_INFO, PlayerUuidArguments.class);
            } else {
                throw new IllegalArgumentException("Unexpected integration module.");
            }
            return () -> { };
        };
    }

    private static void registerCoreProtectTools(ToolRegistry registry) {
        register(registry, ToolName.LOOKUP_AREA_HISTORY, AreaHistoryArguments.class);
        register(registry, ToolName.LOOKUP_PLAYER_HISTORY, PlayerHistoryArguments.class);
    }

    private static void registerWorldGuardTools(ToolRegistry registry) {
        register(registry, ToolName.GET_REGIONS_AT_LOCATION, RegionsAtLocationArguments.class);
        register(registry, ToolName.GET_REGION_INFO, RegionInfoArguments.class);
        register(registry, ToolName.CHECK_BUILD_PERMISSION, BuildPermissionArguments.class);
    }

    private static <A extends ToolArguments> void register(ToolRegistry registry, ToolName tool, Class<A> type) {
        registry.register(tool, type, (context, arguments) -> CompletableFuture.<ToolResult>completedFuture(null));
    }

    private static Set<ToolName> providerTools(ToolRegistry registry) {
        Set<ToolName> registered = registry.tools();
        Set<ToolName> providerTools = new java.util.HashSet<>(registered);
        providerTools.remove(ToolName.GET_SERVER_STATUS);
        return providerTools;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
