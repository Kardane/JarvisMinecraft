package io.github.kardane.jarvisminecraft.paper.integrations.cmi;

import com.Zrips.CMI.Containers.CMIUser;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/** Narrow CMI API boundary. This class is reached only through the optional reflective module. */
public final class CmiApiAccess implements CmiPlayerInfoProvider.ProfileLookup {
    private final Server server;

    private CmiApiAccess(Server server) {
        this.server = server;
    }

    public static Optional<CmiApiAccess> discover(Server server) {
        Plugin cmi = server.getPluginManager().getPlugin("CMI");
        if (cmi == null || !cmi.isEnabled()) {
            return Optional.empty();
        }
        try {
            Method getUser = CMIUser.class.getMethod("getUser", Player.class);
            Method getNickName = CMIUser.class.getMethod("getNickName");
            Method isAfk = CMIUser.class.getMethod("isAfk");
            if (!Modifier.isStatic(getUser.getModifiers())
                || getUser.getReturnType() != CMIUser.class
                || getNickName.getReturnType() != String.class
                || isAfk.getReturnType() != boolean.class) {
                return Optional.empty();
            }
            return Optional.of(new CmiApiAccess(server));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public CmiPlayerInfoProvider.LookupResult lookup(UUID playerUuid) {
        if (!server.isPrimaryThread()) {
            return new CmiPlayerInfoProvider.Unavailable();
        }
        Player player = server.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return new CmiPlayerInfoProvider.Offline();
        }
        CMIUser user = CMIUser.getUser(player);
        if (user == null) {
            return new CmiPlayerInfoProvider.Unavailable();
        }
        return new CmiPlayerInfoProvider.Found(player.getName(), user.getNickName(), user.isAfk());
    }
}
