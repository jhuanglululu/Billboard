package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Drives capture windows across every instance of a target. One target may have many instances —
 * a {@code per_player} placement has one per viewer — so a capture is a set of engine-side windows
 * armed together and collected together.
 *
 * <p>It owns no clock and no thread: the caller ticks it from the scheduler's existing per-tick
 * pass, which is also where the live instance set is already at hand. That is why the whole class
 * is pure and testable with fake {@link StatsSource}s.
 *
 * <p>Two rules earn their keep here rather than in the engine. <b>Late joiners</b>: an instance
 * that starts mid-window is armed for the ticks that are left, so its window ends with everyone
 * else's instead of running past the report. <b>One capture per target</b>: a second request
 * reports the time remaining instead of restarting, so two admins measuring at once cannot
 * silently truncate each other's window.
 *
 * <p>The tick pass is also where entity counts are sampled: the engine measures instructions and
 * bytes but never sees the entities an instance is standing, so each pass adds up what the armed
 * instances have and folds it into the session's running sum and peak.
 */
public final class CaptureOrchestrator {

    /** A snapshot substituted for an instance whose interpreter is gone before collection. */
    private static final StatsSnapshot NO_SNAPSHOT = new StatsSnapshot(0, 0, 0, 0);

    /** An empty, incomplete window: what an instance that never ran a captured tick reports. */
    private static final CaptureSummary NO_CAPTURE =
            new CaptureSummary(0, 0, false, 0, 0, 0, 0, 0);

    /**
     * The outcome of asking for a capture.
     *
     * @param started        whether a new window was armed; false means one was already running
     * @param remainingTicks ticks left on the window that was already running (0 when started)
     * @param armed          instances armed right now — {@code 0} is the case that earns the
     *                       loud "nothing is running this" warning
     */
    public record CaptureStart(boolean started, long remainingTicks, int armed) {}

    /** One armed window: who it covers, when it ends, and where the report goes. */
    private static final class Session {
        final String target;
        final String animation;
        final String placementId; // null = every placement of the animation
        final long windowTicks;
        final long startTick;
        final long deadlineTick;
        final Consumer<CaptureReport> onReport;
        // Report order is arming order; the identity set answers "already armed?" without
        // asking two instances whether they are equal, which they never are. An instance that
        // dies mid-window stays in both, so its truncated window is still collected.
        final List<StatsSource> armed = new ArrayList<>();
        final Set<StatsSource> armedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        // Entity counts are Billboard's to sample: the engine has no idea how many entities an
        // instance is standing. Accumulated in place, so a tick pass allocates nothing.
        long entitySum;
        long entityTicks;
        int entityPeak;

        Session(String target, String animation, String placementId, long windowTicks,
                long startTick, Consumer<CaptureReport> onReport) {
            this.target = target;
            this.animation = animation;
            this.placementId = placementId;
            this.windowTicks = windowTicks;
            this.startTick = startTick;
            this.deadlineTick = startTick + windowTicks;
            this.onReport = onReport;
        }

        boolean covers(StatsSource s) {
            return s.animation().equals(animation)
                    && (placementId == null || placementId.equals(s.placementId()));
        }

        void arm(StatsSource s) {
            armed.add(s);
            armedSet.add(s);
        }

        /** One tick's entity figure: what every armed instance has standing, added up. */
        void sampleEntities() {
            if (armed.isEmpty()) {
                return;     // nothing is running it: an averaged zero would be a made-up sample
            }
            int sum = 0;
            for (int i = 0; i < armed.size(); i++) {
                sum += armed.get(i).liveEntities();
            }
            entitySum += sum;
            entityTicks++;
            if (sum > entityPeak) {
                entityPeak = sum;
            }
        }
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();

    /**
     * Arms a capture on {@code target}, or refuses because one is already running on it.
     *
     * @param target      the word the user typed, and the key one-capture-per-target is keyed on
     * @param animation   the resolved animation name
     * @param placementId the resolved placement id, or {@code null} for the whole animation
     * @param windowTicks how long to capture, in ticks
     * @param currentTick the tick the request arrives on
     * @param live        the instances running right now
     * @param onReport    called once, on the tick the window ends
     */
    public CaptureStart start(String target, String animation, String placementId, int windowTicks,
            long currentTick, Collection<? extends StatsSource> live, Consumer<CaptureReport> onReport) {
        Session running = sessions.get(target);
        if (running != null) {
            return new CaptureStart(false, Math.max(0, running.deadlineTick - currentTick), 0);
        }
        Session session = new Session(target, animation, placementId, windowTicks,
                currentTick, onReport);
        sessions.put(target, session);
        for (StatsSource s : live) {
            if (session.covers(s) && s.startCapture(windowTicks)) {
                session.arm(s);
            }
        }
        return new CaptureStart(true, 0, session.armed.size());
    }

    /** Ticks left on the capture running for {@code target}, or 0 if none is. */
    public long remainingTicks(String target, long currentTick) {
        Session session = sessions.get(target);
        return session == null ? 0 : Math.max(0, session.deadlineTick - currentTick);
    }

    /** Whether any capture is currently armed (the scheduler skips the whole pass otherwise). */
    public boolean idle() {
        return sessions.isEmpty();
    }

    /**
     * Advances every armed window: arms instances that have started since, and delivers the report
     * for any window whose deadline has come. Called once per tick from the scheduler's tick pass.
     */
    public void tick(long currentTick, Collection<? extends StatsSource> live) {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session session : List.copyOf(sessions.values())) {
            long remaining = session.deadlineTick - currentTick;
            if (remaining >= 1) {
                for (StatsSource s : live) {
                    if (session.covers(s) && !session.armedSet.contains(s)
                            && s.startCapture((int) remaining)) {
                        session.arm(s);
                    }
                }
            }
            if (currentTick <= session.deadlineTick) {
                session.sampleEntities();
            }
            // One tick past the deadline: the tick that takes the last sample is dispatched
            // after this pass, so collecting on the deadline itself would read a window that
            // has not closed yet.
            if (currentTick > session.deadlineTick) {
                sessions.remove(session.target);
                session.onReport.accept(collect(session, currentTick, false));
            }
        }
    }

    /**
     * Ends the window on {@code target} now, keeping every sample taken so far, and delivers the
     * report to whoever started it — the report belongs to the request, not to whoever cut it short.
     *
     * @return false if nothing was running on that target
     */
    public boolean stop(String target, long currentTick) {
        Session session = sessions.remove(target);
        if (session == null) {
            return false;
        }
        for (StatsSource s : session.armed) {
            s.stopCapture();
        }
        session.onReport.accept(collect(session, currentTick, true));
        return true;
    }

    /** Drops every armed window without reporting (plugin disable). */
    public void clear() {
        sessions.clear();
    }

    private static CaptureReport collect(Session session, long currentTick, boolean stopped) {
        List<CaptureReport.InstanceStats> rows = new ArrayList<>();
        for (StatsSource s : session.armed) {
            rows.add(row(s));
        }
        long elapsed = Math.min(session.windowTicks, Math.max(0, currentTick - session.startTick));
        return new CaptureReport(session.target, session.windowTicks, elapsed, stopped, rows,
                new CaptureReport.EntitySamples(session.entitySum, session.entityTicks,
                        session.entityPeak));
    }

    private static CaptureReport.InstanceStats row(StatsSource s) {
        return new CaptureReport.InstanceStats(s.label(),
                s.animation() + "/" + s.placementId(),
                s.captureResult().orElse(NO_CAPTURE),
                s.stats().orElse(NO_SNAPSHOT),
                s.liveEntities());
    }
}
