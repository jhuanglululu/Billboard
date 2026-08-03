package com.jhuanglululu.billboard.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Reads online players and their positions from the Bukkit {@link Server}. Must be used
 * from the main thread (the proximity task runs there); {@link ProximityController} takes
 * the {@link PositionSource} interface so tests use a fake instead.
 */
public final class BukkitPositionSource implements PositionSource {

    private final Server server;

    public BukkitPositionSource(Server server) {
        this.server = server;
    }

    @Override
    public Collection<ViewerPosition> onlinePlayers() {
        List<ViewerPosition> out = new ArrayList<>();
        for (Player p : server.getOnlinePlayers()) {
            Location loc = p.getLocation();
            String world = loc.getWorld() == null ? "" : loc.getWorld().getName();
            // getEyeHeight() is the live value, so a sneaking player reports the lower eyes the
            // guest would otherwise have to guess at.
            out.add(new ViewerPosition(p.getUniqueId(), p.getName(), world,
                    loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch(), p.getEyeHeight()));
        }
        return out;
    }
}
