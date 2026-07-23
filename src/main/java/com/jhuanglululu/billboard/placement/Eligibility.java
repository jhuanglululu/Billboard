package com.jhuanglululu.billboard.placement;

import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.Map;
import java.util.Set;

/**
 * Pure eligibility rules: whether a player may see a placement (visibility filter with
 * group expansion) and whether they are within range. No Bukkit, fully testable.
 */
public final class Eligibility {

    private Eligibility() {}

    /**
     * Whether {@code playerName} passes the visibility filter. Whitelist/blacklist entries
     * match the player directly or via any group that contains them.
     */
    public static boolean visibleTo(VisibilityMode mode, Set<String> whitelist, Set<String> blacklist,
            Map<String, Set<String>> groups, String playerName) {
        return switch (mode) {
            case EVERYONE -> true;
            case NONE -> false;
            case WHITELIST -> listMatches(whitelist, groups, playerName);
            case BLACKLIST -> !listMatches(blacklist, groups, playerName);
        };
    }

    private static boolean listMatches(Set<String> list, Map<String, Set<String>> groups, String player) {
        if (list.contains(player)) {
            return true;
        }
        for (String entry : list) {
            Set<String> members = groups.get(entry);
            if (members != null && members.contains(player)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code viewer} is in the placement's world and within {@code radius} blocks of its origin. */
    public static boolean inRange(Placement placement, ViewerPosition viewer, double radius) {
        if (!placement.world().equals(viewer.world())) {
            return false;
        }
        double dx = viewer.x() - placement.x();
        double dy = viewer.y() - placement.y();
        double dz = viewer.z() - placement.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
