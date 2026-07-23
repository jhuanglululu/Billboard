package com.jhuanglululu.billboard.placement;

import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.data.AnimationSettings;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The proximity state machine: on each check it starts an instance when an eligible player
 * comes into range, keeps it while anyone eligible is near, and — when the last one leaves
 * — lets it <em>linger</em> (still running) for {@code linger-ticks} before stopping it, so
 * a player jittering at the border never thrashes restarts. A pause on the animation stops
 * its instances immediately.
 *
 * <p>{@code shared} placements have one instance for the whole eligible audience;
 * {@code per_player} placements have one instance per eligible player. All server contact
 * goes through {@link PositionSource} and {@link InstanceLifecycle}, so this class is pure
 * and unit-testable with fakes and a caller-supplied tick.
 *
 * <p>Instance self-termination (a finished/errored animation) is reconciled by the scheduler
 * glue via {@link #forget}; this controller only owns the proximity-driven transitions.
 */
public final class ProximityController<H> {

    private static final class Running<H> {
        final H handle;
        long deathTick = -1; // when lingering must end; -1 = has viewers / not lingering

        Running(H handle) {
            this.handle = handle;
        }
    }

    private final PositionSource positions;
    private final InstanceLifecycle<H> lifecycle;
    private final DataStore data;
    private final Supplier<BillboardConfig> config;

    private final Map<String, Running<H>> shared = new HashMap<>();
    private final Map<String, Map<UUID, Running<H>>> perPlayer = new HashMap<>();

    public ProximityController(PositionSource positions, InstanceLifecycle<H> lifecycle,
            DataStore data, Supplier<BillboardConfig> config) {
        this.positions = positions;
        this.lifecycle = lifecycle;
        this.data = data;
        this.config = config;
    }

    /** Re-evaluate every placement at {@code currentTick}, starting/lingering/stopping instances. */
    public void check(long currentTick) {
        BillboardConfig cfg = config.get();
        double radius = cfg.proximity().radius();
        long linger = cfg.proximity().lingerTicks();
        var online = positions.onlinePlayers();

        Set<String> livePlacements = new HashSet<>();
        for (Placement p : data.placements()) {
            livePlacements.add(p.key());
            boolean paused = data.existingAnimation(p.animation())
                    .map(AnimationSettings::paused).orElse(false);
            List<ViewerPosition> eligible = paused ? List.of() : eligibleViewers(p, online, radius);
            if (p.type() == InstanceType.SHARED) {
                driveShared(p, eligible, paused, currentTick, linger);
            } else {
                drivePerPlayer(p, eligible, paused, currentTick, linger);
            }
        }
        stopOrphaned(livePlacements);
    }

    private List<ViewerPosition> eligibleViewers(Placement p, Iterable<ViewerPosition> online, double radius) {
        AnimationSettings s = data.existingAnimation(p.animation()).orElse(null);
        Set<String> whitelist = s != null ? s.whitelist() : Set.of();
        Set<String> blacklist = s != null ? s.blacklist() : Set.of();
        List<ViewerPosition> out = new ArrayList<>();
        for (ViewerPosition v : online) {
            if (Eligibility.inRange(p, v, radius)
                    && Eligibility.visibleTo(p.visibility(), whitelist, blacklist, data.groupsView(), v.name())) {
                out.add(v);
            }
        }
        return out;
    }

    private void driveShared(Placement p, List<ViewerPosition> eligible, boolean paused,
            long tick, long linger) {
        Running<H> r = shared.get(p.key());
        if (!eligible.isEmpty()) {
            Set<ViewerPosition> viewers = new HashSet<>(eligible);
            if (r == null) {
                shared.put(p.key(), new Running<>(lifecycle.start(p, viewers)));
            } else {
                r.deathTick = -1;
                lifecycle.setViewers(r.handle, viewers);
            }
        } else if (r != null) {
            if (paused) {
                lifecycle.stop(r.handle);
                shared.remove(p.key());
            } else if (r.deathTick < 0) {
                r.deathTick = tick + linger; // begin lingering; instance keeps running
            } else if (tick >= r.deathTick) {
                lifecycle.stop(r.handle);
                shared.remove(p.key());
            }
        }
    }

    private void drivePerPlayer(Placement p, List<ViewerPosition> eligible, boolean paused,
            long tick, long linger) {
        Map<UUID, Running<H>> byPlayer = perPlayer.computeIfAbsent(p.key(), k -> new HashMap<>());
        if (paused) {
            for (Running<H> r : byPlayer.values()) {
                lifecycle.stop(r.handle);
            }
            byPlayer.clear();
            return;
        }
        Set<UUID> stillEligible = new HashSet<>();
        for (ViewerPosition v : eligible) {
            stillEligible.add(v.uuid());
            Running<H> r = byPlayer.get(v.uuid());
            if (r == null) {
                byPlayer.put(v.uuid(), new Running<>(lifecycle.start(p, Set.of(v))));
            } else {
                r.deathTick = -1;
                lifecycle.setViewers(r.handle, Set.of(v));
            }
        }
        Iterator<Map.Entry<UUID, Running<H>>> it = byPlayer.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Running<H>> e = it.next();
            if (stillEligible.contains(e.getKey())) {
                continue;
            }
            Running<H> r = e.getValue();
            if (r.deathTick < 0) {
                r.deathTick = tick + linger;
            } else if (tick >= r.deathTick) {
                lifecycle.stop(r.handle);
                it.remove();
            }
        }
    }

    private void stopOrphaned(Set<String> livePlacements) {
        shared.entrySet().removeIf(e -> {
            if (livePlacements.contains(e.getKey())) {
                return false;
            }
            lifecycle.stop(e.getValue().handle);
            return true;
        });
        perPlayer.entrySet().removeIf(e -> {
            if (livePlacements.contains(e.getKey())) {
                return false;
            }
            for (Running<H> r : e.getValue().values()) {
                lifecycle.stop(r.handle);
            }
            return true;
        });
    }

    /** Drop tracking for an instance that ended on its own (finished/errored); no stop call. */
    public void forget(H handle) {
        shared.values().removeIf(r -> r.handle.equals(handle));
        for (Map<UUID, Running<H>> byPlayer : perPlayer.values()) {
            byPlayer.values().removeIf(r -> r.handle.equals(handle));
        }
    }

    /** Stop every running instance (plugin disable). */
    public void stopAll() {
        for (Running<H> r : shared.values()) {
            lifecycle.stop(r.handle);
        }
        shared.clear();
        for (Map<UUID, Running<H>> byPlayer : perPlayer.values()) {
            for (Running<H> r : byPlayer.values()) {
                lifecycle.stop(r.handle);
            }
        }
        perPlayer.clear();
    }

    /** The number of instances currently tracked as running (for pool sizing). */
    public int activeInstanceCount() {
        int count = shared.size();
        for (Map<UUID, Running<H>> byPlayer : perPlayer.values()) {
            count += byPlayer.size();
        }
        return count;
    }
}
