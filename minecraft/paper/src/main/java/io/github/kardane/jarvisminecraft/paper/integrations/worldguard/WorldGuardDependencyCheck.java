package io.github.kardane.jarvisminecraft.paper.integrations.worldguard;

public final class WorldGuardDependencyCheck {
    private WorldGuardDependencyCheck() {
    }

    public static boolean isAvailable(boolean worldGuardEnabled, boolean worldEditEnabled) {
        return worldGuardEnabled && worldEditEnabled;
    }
}
