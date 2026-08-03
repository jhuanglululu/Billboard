package com.jhuanglululu.billboard.placement;

import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which players one running instance is allowed to see, given the whole online list.
 *
 * <p>It is deliberately the same rule the proximity pass already applies — in range and past the
 * visibility filter — so a guest can never learn about somebody it is not showing anything to. The
 * two instance types differ only in the last step:
 *
 * <ul>
 *   <li><b>{@code shared}</b> — every eligible viewer, which is exactly its audience;</li>
 *   <li><b>{@code per_player}</b> — just the owner, because that instance exists for one person and
 *       telling it about the others would leak them into an animation only one player can see.</li>
 * </ul>
 *
 * <p>Pure: no Bukkit, so the selection is testable without a server. The main thread runs this
 * every {@code snapshots.player-interval} ticks and hands each result to its instance.
 */
public final class PlayerSnapshots {

    private PlayerSnapshots() {}

    /**
     * The viewers {@code placement}'s instance owned by {@code ownerLabel} may be told about.
     *
     * @param ownerLabel the owning player's name for a {@code per_player} instance; ignored for a
     *                   {@code shared} one (where it is the {@code EVERYONE} placeholder)
     * @param online     every online player's snapshot, taken once for the whole pass
     * @param groups     group id to member names, for the visibility filter
     * @param radius     the proximity radius in blocks
     */
    public static List<ViewerPosition> forInstance(Placement placement, String ownerLabel,
            Collection<ViewerPosition> online, Map<String, Set<String>> groups, double radius) {
        boolean perPlayer = placement.type() == InstanceType.PER_PLAYER;
        List<ViewerPosition> out = new ArrayList<>();
        for (ViewerPosition v : online) {
            if (perPlayer && !v.name().equals(ownerLabel)) {
                continue;
            }
            if (Eligibility.inRange(placement, v, radius)
                    && Eligibility.visibleTo(placement.visibility(), placement.whitelist(),
                            placement.blacklist(), groups, v.name())) {
                out.add(v);
            }
        }
        return out;
    }
}
