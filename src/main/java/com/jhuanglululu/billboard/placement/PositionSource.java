package com.jhuanglululu.billboard.placement;

import java.util.Collection;

/**
 * Supplies the currently online players and their positions. The real implementation
 * reads Bukkit; tests supply a fake, which is why {@link ProximityController} depends on
 * this interface rather than the server directly.
 */
@FunctionalInterface
public interface PositionSource {

    Collection<ViewerPosition> onlinePlayers();
}
