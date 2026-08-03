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
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The proximity state machine: on each check it starts an instance when an eligible player
 * comes into range, keeps it while anyone eligible is near, and — when the last one leaves
 * — lets it <em>linger</em> (still running) for {@code linger-ticks} before stopping it, so
 * a player jittering at the border never thrashes restarts. A pause — on the animation or on the
 * single placement — stops its instances immediately, and nudges nearby admins once.
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

    private BiConsumer<String, String> startFailureHandler = (animation, message) -> { };
    // Placement keys load-time validation rejected; re-supplied on every reload.
    private Supplier<Set<String>> skippedPlacements = Set::of;

    private PauseHintSink pauseHints = (placement, viewer, animationLevel) -> false;
    // placement key -> players already nudged about it; dropped as soon as it is unpaused.
    private final Map<String, Set<UUID>> hinted = new HashMap<>();

    public ProximityController(PositionSource positions, InstanceLifecycle<H> lifecycle,
            DataStore data, Supplier<BillboardConfig> config) {
        this.positions = positions;
        this.lifecycle = lifecycle;
        this.data = data;
        this.config = config;
    }

    /**
     * Called (once) with {@code (animation, message)} when {@link InstanceLifecycle#start}
     * fails; the controller has already paused the animation so it won't be retried, so the
     * handler need only persist and report the failure.
     */
    public void setStartFailureHandler(BiConsumer<String, String> startFailureHandler) {
        this.startFailureHandler = startFailureHandler;
    }

    /**
     * The placements load-time validation skipped. They behave exactly as paused — no eligible
     * viewers, so no instance is ever started — until a successful reload replaces the set.
     */
    public void setSkippedPlacements(Supplier<Set<String>> skippedPlacements) {
        this.skippedPlacements = skippedPlacements;
    }

    /**
     * Where the paused-placement nudge goes. It rides the existing proximity check — the same
     * pass that decides an instance should exist already knows who is standing where — so no
     * extra scheduled task exists just to notice someone approaching a paused placement.
     */
    public void setPauseHintSink(PauseHintSink pauseHints) {
        this.pauseHints = pauseHints;
    }

    /**
     * Forget who has been nudged about what, so every eligible player is told again. Called on
     * {@code /billboard reload}, which may well have changed why a placement is paused.
     */
    public void clearPauseHints() {
        hinted.clear();
    }

    /**
     * Starts an instance, catching any failure: a broken start pauses the animation (so the
     * next check computes no eligible viewers and never retries) and reports exactly once.
     * Returns {@code null} when the start failed, so the caller must not track it.
     *
     * <p><b>Last-resort defense only.</b> Everything that can be known without a player — the
     * module parsing, its ABI handshake, the placement's animation and world — is checked when
     * animations load, so reaching this catch means something changed underneath a validated
     * load. It stays because a silent broken start would be worse, not because it is the report
     * path a user should ever see.
     */
    private H tryStart(Placement placement, Set<ViewerPosition> viewers) {
        try {
            return lifecycle.start(placement, viewers);
        } catch (RuntimeException e) {
            data.animation(placement.animation()).setPaused(true);
            startFailureHandler.accept(placement.animation(), String.valueOf(e.getMessage()));
            return null;
        }
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
            boolean animationPaused = data.existingAnimation(p.animation())
                    .map(AnimationSettings::paused).orElse(false);
            // Three ways to be out of service, one behaviour: instantiate nothing. The command
            // flags additionally nudge nearby admins; a load-time skip already reported itself.
            boolean paused = animationPaused || p.paused() || skippedPlacements.get().contains(p.key());
            if (animationPaused || p.paused()) {
                nudge(p, online, radius, animationPaused);
            } else {
                hinted.remove(p.key()); // resumed: a later pause nudges again
            }
            List<ViewerPosition> eligible = paused ? List.of() : eligibleViewers(p, online, radius);
            if (p.type() == InstanceType.SHARED) {
                driveShared(p, eligible, paused, currentTick, linger);
            } else {
                drivePerPlayer(p, eligible, paused, currentTick, linger);
            }
        }
        stopOrphaned(livePlacements);
    }

    /**
     * Offers the pause nudge to everyone in range of {@code p} who has not had it yet. Range, not
     * eligibility: the admin debugging a paused placement is often not one of its viewers, and the
     * visibility filter is about who sees the animation, not who is told why there is none.
     */
    private void nudge(Placement p, Iterable<ViewerPosition> online, double radius, boolean animationLevel) {
        Set<UUID> alreadyTold = hinted.computeIfAbsent(p.key(), k -> new HashSet<>());
        for (ViewerPosition v : online) {
            if (alreadyTold.contains(v.uuid()) || !Eligibility.inRange(p, v, radius)) {
                continue;
            }
            if (pauseHints.hint(p, v, animationLevel)) {
                alreadyTold.add(v.uuid());
            }
        }
    }

    private List<ViewerPosition> eligibleViewers(Placement p, Iterable<ViewerPosition> online, double radius) {
        List<ViewerPosition> out = new ArrayList<>();
        for (ViewerPosition v : online) {
            if (Eligibility.inRange(p, v, radius)
                    && Eligibility.visibleTo(p.visibility(), p.whitelist(), p.blacklist(),
                            data.groupsView(), v.name())) {
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
                H handle = tryStart(p, viewers);
                if (handle != null) {
                    shared.put(p.key(), new Running<>(handle));
                }
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
                H handle = tryStart(p, Set.of(v));
                if (handle != null) {
                    byPlayer.put(v.uuid(), new Running<>(handle));
                }
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
        hinted.keySet().retainAll(livePlacements);
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

    /**
     * Stop and forget every instance of {@code animation} (used on reload when its module was
     * changed or removed); the proximity path restarts still-present placements next check.
     */
    public void stopInstancesOf(String animation) {
        String prefix = animation + "/";
        shared.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                lifecycle.stop(e.getValue().handle);
                return true;
            }
            return false;
        });
        perPlayer.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                for (Running<H> r : e.getValue().values()) {
                    lifecycle.stop(r.handle);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Stop and forget every instance of one placement, so the next proximity check starts it again
     * from scratch. This is how an env change reaches a running guest: environ is immutable for the
     * lifetime of a run by design, so the only way to show new values is a new run.
     *
     * @param key the placement's {@code animation/id} key
     * @return how many instances were stopped (a {@code per_player} placement can have several)
     */
    public int stopInstancesOfPlacement(String key) {
        int stopped = 0;
        Running<H> r = shared.remove(key);
        if (r != null) {
            lifecycle.stop(r.handle);
            stopped++;
        }
        Map<UUID, Running<H>> byPlayer = perPlayer.remove(key);
        if (byPlayer != null) {
            for (Running<H> each : byPlayer.values()) {
                lifecycle.stop(each.handle);
                stopped++;
            }
        }
        return stopped;
    }

    /** {@link #stopInstancesOfPlacement} for every placement of {@code animation}. */
    public int stopInstancesOfAnimation(String animation) {
        int stopped = 0;
        for (String key : List.copyOf(allTrackedKeys())) {
            if (key.startsWith(animation + "/")) {
                stopped += stopInstancesOfPlacement(key);
            }
        }
        return stopped;
    }

    private Set<String> allTrackedKeys() {
        Set<String> keys = new HashSet<>(shared.keySet());
        keys.addAll(perPlayer.keySet());
        return keys;
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
