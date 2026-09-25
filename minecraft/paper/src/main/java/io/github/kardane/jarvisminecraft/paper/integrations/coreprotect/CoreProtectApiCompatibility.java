package io.github.kardane.jarvisminecraft.paper.integrations.coreprotect;

public final class CoreProtectApiCompatibility {
    private CoreProtectApiCompatibility() {
    }

    public static boolean isSupported(
        boolean pluginPresent,
        boolean expectedPluginType,
        boolean pluginEnabled,
        boolean apiEnabled,
        int apiVersion
    ) {
        return pluginPresent
            && expectedPluginType
            && pluginEnabled
            && apiEnabled
            && apiVersion >= CoreProtectHistoryProvider.MINIMUM_API_VERSION;
    }
}
