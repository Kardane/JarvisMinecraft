package io.github.kardane.jarvisminecraft.paper.integrations;

import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.tools.PaperToolService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Assembles the Paper Tool registry while keeping optional plugin APIs isolated. */
public final class IntegrationRegistry implements AutoCloseable {
    private static final List<ProviderModule> OPTIONAL_MODULES = List.of(
        new ProviderModule(
            "CoreProtect",
            "coreprotect",
            Set.of("coreprotect"),
            Set.of("history.lookup"),
            "io.github.kardane.jarvisminecraft.paper.integrations.coreprotect.CoreProtectIntegrationModule"
        ),
        new ProviderModule(
            "WorldGuard",
            "worldguard",
            Set.of("worldguard", "worldedit"),
            Set.of("region.lookup", "region.protection"),
            "io.github.kardane.jarvisminecraft.paper.integrations.worldguard.WorldGuardIntegrationModule"
        ),
        new ProviderModule(
            "CMI",
            "cmi",
            Set.of("cmi", "cmilib"),
            Set.of("player.cmi_profile"),
            "io.github.kardane.jarvisminecraft.paper.integrations.cmi.CmiIntegrationModule"
        )
    );

    private final ToolRegistry toolRegistry;
    private final List<AutoCloseable> providers;
    private final List<ProviderState> providerStates;
    private final Consumer<String> warnings;
    private boolean closed;

    private IntegrationRegistry(
        ToolRegistry toolRegistry,
        List<AutoCloseable> providers,
        List<ProviderState> providerStates,
        Consumer<String> warnings
    ) {
        this.toolRegistry = toolRegistry;
        this.providers = new ArrayList<>(providers);
        this.providerStates = List.copyOf(providerStates);
        this.warnings = warnings;
    }

    public static IntegrationRegistry create(
        Server server,
        PaperPlatformAccess platform,
        Clock clock,
        Logger logger
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(logger, "logger");

        ToolRegistry registry = new ToolRegistry();
        new PaperToolService(platform, clock).register(registry);

        Set<String> installed = new LinkedHashSet<>();
        Set<String> enabled = new LinkedHashSet<>();
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (Plugin plugin : server.getPluginManager().getPlugins()) {
            if (plugin == null || plugin.getName() == null) {
                continue;
            }
            String key = pluginKey(plugin.getName());
            installed.add(key);
            String version = plugin.getDescription().getVersion();
            if (version != null && !version.isBlank()) {
                versions.put(key, version);
            }
            if (plugin.isEnabled()) {
                enabled.add(key);
            }
        }

        return assemble(
            registry,
            installed,
            enabled,
            versions,
            OPTIONAL_MODULES,
            server,
            clock,
            IntegrationRegistry::loadModule,
            logger::warning
        );
    }

    static IntegrationRegistry assemble(
        ToolRegistry registry,
        Set<String> installedPlugins,
        Set<String> enabledPlugins,
        Map<String, String> pluginVersions,
        Collection<ProviderModule> modules,
        Server server,
        Clock clock,
        ModuleLoader loader,
        Consumer<String> warnings
    ) {
        Objects.requireNonNull(registry, "registry");
        Set<String> installed = normalized(installedPlugins);
        Set<String> enabled = normalized(enabledPlugins);
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        if (pluginVersions != null) {
            pluginVersions.forEach((plugin, version) -> {
                if (plugin != null && version != null && !version.isBlank()) {
                    versions.put(pluginKey(plugin), version);
                }
            });
        }
        List<AutoCloseable> providers = new ArrayList<>();
        List<ProviderState> states = new ArrayList<>();

        for (ProviderModule module : modules) {
            ProviderState state;
            if (!installed.contains(module.primaryPlugin())) {
                state = providerState(module, ProviderAvailability.ABSENT, versions);
            } else if (!enabled.contains(module.primaryPlugin())) {
                state = providerState(module, ProviderAvailability.DISABLED, versions);
            } else if (!enabled.containsAll(module.requiredPlugins())) {
                state = providerState(module, ProviderAvailability.DEPENDENCY_MISSING, versions);
            } else {
                ToolRegistry stagedTools = new ToolRegistry();
                AutoCloseable provider = null;
                try {
                    provider = loader.load(module, stagedTools, server, clock);
                    if (provider == null) {
                        state = providerState(module, ProviderAvailability.API_UNAVAILABLE, versions);
                    } else {
                        registry.registerAll(stagedTools);
                        providers.add(provider);
                        state = providerState(module, ProviderAvailability.ACTIVE, versions);
                    }
                } catch (Exception | LinkageError failure) {
                    closeProvider(provider, warnings);
                    state = providerState(module, ProviderAvailability.API_UNAVAILABLE, versions);
                }
            }
            states.add(state);
            if (state.availability() != ProviderAvailability.ACTIVE
                && state.availability() != ProviderAvailability.ABSENT) {
                warnings.accept("Optional " + state.provider() + " integration is unavailable ("
                    + state.availability() + "); its Tools were not registered.");
            }
        }

        return new IntegrationRegistry(registry, providers, states, warnings);
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public List<ProviderState> providerStates() {
        return providerStates;
    }

    /** Returns only capabilities represented by currently registered Tools. */
    public List<ProtocolMessage.Capability> capabilities(String paperMinecraftVersion) {
        Objects.requireNonNull(paperMinecraftVersion, "paperMinecraftVersion");
        return toolRegistry.tools().stream()
            .map(tool -> tool.capability())
            .distinct()
            .sorted()
            .map(capability -> providerStates.stream()
                .filter(state -> state.availability() == ProviderAvailability.ACTIVE)
                .filter(state -> state.capabilities().contains(capability))
                .findFirst()
                .map(state -> new ProtocolMessage.Capability(
                    capability,
                    state.provider(),
                    state.version()
                ))
                .orElseGet(() -> new ProtocolMessage.Capability(
                    capability,
                    "Paper",
                    paperMinecraftVersion
                )))
            .toList();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int index = providers.size() - 1; index >= 0; index--) {
            closeProvider(providers.get(index), warnings);
        }
        providers.clear();
    }

    private static void closeProvider(AutoCloseable provider, Consumer<String> warnings) {
        if (provider == null) {
            return;
        }
        try {
            provider.close();
        } catch (Exception | LinkageError failure) {
            warnings.accept("An optional provider failed to stop cleanly.");
        }
    }

    private static ProviderState providerState(
        ProviderModule module,
        ProviderAvailability availability,
        Map<String, String> versions
    ) {
        return new ProviderState(
            module.name(),
            availability,
            versions.get(module.primaryPlugin()),
            module.capabilities()
        );
    }

    private static AutoCloseable loadModule(
        ProviderModule module,
        ToolRegistry registry,
        Server server,
        Clock clock
    ) throws ReflectiveOperationException {
        Class<?> moduleClass = Class.forName(
            module.entrypointClass(),
            true,
            IntegrationRegistry.class.getClassLoader()
        );
        Method install = moduleClass.getMethod("install", ToolRegistry.class, Server.class, Clock.class);
        try {
            return (AutoCloseable) install.invoke(null, registry, server, clock);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof LinkageError linkageError) {
                throw linkageError;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Provider module failed to initialize.", cause);
        }
    }

    private static Set<String> normalized(Collection<String> plugins) {
        Set<String> normalized = new LinkedHashSet<>();
        if (plugins != null) {
            plugins.stream()
                .filter(Objects::nonNull)
                .map(IntegrationRegistry::pluginKey)
                .forEach(normalized::add);
        }
        return Set.copyOf(normalized);
    }

    private static String pluginKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    record ProviderModule(
        String name,
        String primaryPlugin,
        Set<String> requiredPlugins,
        Set<String> capabilities,
        String entrypointClass
    ) {
        ProviderModule {
            Objects.requireNonNull(name, "name");
            primaryPlugin = pluginKey(Objects.requireNonNull(primaryPlugin, "primaryPlugin"));
            requiredPlugins = normalized(requiredPlugins);
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            if (!requiredPlugins.contains(primaryPlugin)) {
                throw new IllegalArgumentException("Required plugins must include the primary plugin.");
            }
            Objects.requireNonNull(entrypointClass, "entrypointClass");
        }
    }

    @FunctionalInterface
    interface ModuleLoader {
        AutoCloseable load(ProviderModule module, ToolRegistry registry, Server server, Clock clock)
            throws Exception;
    }

    public enum ProviderAvailability {
        ABSENT,
        DISABLED,
        DEPENDENCY_MISSING,
        ACTIVE,
        API_UNAVAILABLE
    }

    public record ProviderState(
        String provider,
        ProviderAvailability availability,
        String version,
        Set<String> capabilities
    ) {
        public ProviderState {
            capabilities = Set.copyOf(capabilities);
        }
    }
}
