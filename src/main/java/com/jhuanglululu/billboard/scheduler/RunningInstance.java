package com.jhuanglululu.billboard.scheduler;

import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.render.Origin;
import com.jhuanglululu.billboard.placement.ViewerPosition;
import com.jhuanglululu.billboard.render.PacketEventsRenderer;
import com.jhuanglululu.billboard.render.PlayerFrame;
import com.jhuanglululu.billboard.render.Rotation;
import com.jhuanglululu.billboard.runtime.AnimationInstance;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.billboard.runtime.PlayerView;
import com.jhuanglululu.billboard.stats.StatsSource;
import com.jhuanglululu.wasmachine.runtime.LogSink;
import com.jhuanglululu.billboard.runtime.TickResult;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;

/**
 * One live animation instance and its packet renderer, plus the buffering that keeps guest
 * {@code log} output off the worker thread. Ticked on a worker by {@link AnimationScheduler};
 * viewer changes and cleanup happen on the main thread.
 */
public final class RunningInstance implements StatsSource {

    private final Placement placement;
    private final String ownerLabel;
    private final Module module;
    private final BlockStateValidator validator;
    private final ContentValidator content;
    private final long memoryCapBytes;
    private final Map<String, String> environ;
    private final int taskStackBytes;
    private final Origin origin;
    private final PacketEventsRenderer renderer;
    private final Queue<String> logBuffer = new ConcurrentLinkedQueue<>();

    // Written on the main thread by the snapshot pass, read on a worker during a tick. An
    // immutable list behind an atomic reference is the whole synchronisation story: the worker
    // either sees the previous list or the new one, never a half-built one, and neither side ever
    // waits for the other.
    private final AtomicReference<List<PlayerView>> players =
            new AtomicReference<>(List.of());

    private AnimationInstance instance;

    // What the interpreter last reported, kept so an instance that dies (or is released with its
    // entities standing) can still be part of a capture report instead of vanishing from it.
    private MachineInstance.StatsSnapshot lastSnapshot;
    private MachineInstance.CaptureSummary lastCapture;
    private int lastLiveEntities;
    // The tick this run began on, stamped by the scheduler; uptime is the difference from now.
    private long startTick;

    /**
     * @param environ        the effective env this run's guest sees; fixed for the whole run, so a
     *                       {@code /billboard env} change stops the instance rather than mutating it
     * @param taskStackBytes the per-spawned-task stack size from {@code runtime.task-stack-bytes}
     */
    public RunningInstance(Placement placement, String ownerLabel, Module module,
            BlockStateValidator validator, ContentValidator content, long memoryCapBytes,
            Map<String, String> environ, int taskStackBytes) {
        this.placement = placement;
        this.ownerLabel = ownerLabel;
        this.module = module;
        this.validator = validator;
        this.content = content;
        this.memoryCapBytes = memoryCapBytes;
        this.environ = Map.copyOf(environ);
        this.taskStackBytes = taskStackBytes;
        this.origin = new Origin(placement.world(), placement.x(), placement.y(), placement.z(),
                new Rotation(placement.yaw(), placement.pitch(), placement.roll()));
        this.renderer = new PacketEventsRenderer(origin);
        this.instance = build();
    }

    private AnimationInstance build() {
        LogSink sink = (animation, message) -> logBuffer.add(message);
        // The seed is stable across restarts and across restarts of this placement, so a
        // per_player billboard shows each player the same variation on every visit.
        long seed = AnimationInstance.stableSeed(placement.animation(), placement.id(), ownerLabel);
        return new AnimationInstance(placement.animation(), module, renderer, validator, content,
                sink, memoryCapBytes, seed, environ, taskStackBytes, players::get);
    }

    /**
     * Replaces this instance's player snapshot (main thread). The world-space viewers are converted
     * into the placement's own frame here, once per swap, rather than on every guest call — the
     * conversion is the same for every reader and the guest never sees world coordinates anyway.
     */
    public void setPlayerSnapshot(Collection<ViewerPosition> viewers) {
        List<PlayerView> converted = new ArrayList<>(viewers.size());
        for (ViewerPosition v : viewers) {
            converted.add(PlayerFrame.toLocal(origin, v));
        }
        players.set(List.copyOf(converted));
    }

    /**
     * Advance one tick (runs on a worker thread; sends packets during execution), then step the
     * renderer's host tweens. Tweens ride the animation's own tick, so a dead or paused instance
     * simply stops moving — no separate timer to leak.
     */
    public TickResult tick(long currentTick, long budget) {
        TickResult result = instance.tick(currentTick, budget);
        renderer.tickTweens();
        return result;
    }

    /** Update the audience (main thread). */
    public void setViewers(Set<Player> players) {
        renderer.setViewers(players);
    }

    /**
     * Restart from scratch (ExitCode.Repeat): clear entities and rebuild the interpreter. The run
     * this reports on is the new one, so the caller re-stamps {@link #markStarted}.
     */
    public void restart() {
        remember();
        renderer.destroyAll();
        instance = build();
    }

    /** Records the tick this run began on (the scheduler knows it; the instance does not). */
    public void markStarted(long tick) {
        this.startTick = tick;
    }

    /** Release the interpreter but keep the rendered entities visible (ExitCode.Keep). */
    public void releaseRuntimeKeepEntities() {
        remember();
        instance = null;
    }

    /** Stop and clean up: despawn every entity this instance ever spawned. */
    public void stop() {
        if (instance != null) {
            remember(); // before cleanup, or the entity counts read as zero
            instance.cleanup();
            instance = null;
        }
        renderer.destroyAll();
    }

    /**
     * Saves the numbers the interpreter is about to take with it. Called on every path that drops
     * the {@link AnimationInstance}: a capture window that outlives the instance it was measuring
     * still has something to report, which is exactly the "died mid-capture" case.
     */
    private void remember() {
        if (instance == null) {
            return;
        }
        lastSnapshot = instance.stats();
        instance.captureResult().ifPresent(summary -> lastCapture = summary);
        lastLiveEntities = instance.registry().liveIds().size();
    }

    /** Drain and return the guest log messages buffered since the last drain (main thread). */
    public List<String> drainLogs() {
        List<String> out = new ArrayList<>();
        String msg;
        while ((msg = logBuffer.poll()) != null) {
            out.add(msg);
        }
        return out;
    }

    public boolean isTickable() {
        return instance != null;
    }

    public Placement placement() {
        return placement;
    }

    public String ownerLabel() {
        return ownerLabel;
    }

    // --- StatsSource: what /billboard stats reads (main thread) ---

    @Override
    public String animation() {
        return placement.animation();
    }

    @Override
    public String placementId() {
        return placement.id();
    }

    @Override
    public String label() {
        return placement.animation() + "/" + placement.id() + ":" + ownerLabel;
    }

    @Override
    public Optional<MachineInstance.StatsSnapshot> stats() {
        return instance != null ? Optional.of(instance.stats()) : Optional.ofNullable(lastSnapshot);
    }

    @Override
    public int liveEntities() {
        return instance != null ? instance.registry().liveIds().size() : lastLiveEntities;
    }

    @Override
    public long startTick() {
        return startTick;
    }

    @Override
    public boolean startCapture(int ticks) {
        return instance != null && instance.startCapture(ticks);
    }

    @Override
    public boolean stopCapture() {
        return instance != null && instance.stopCapture();
    }

    @Override
    public Optional<MachineInstance.CaptureSummary> captureResult() {
        if (instance != null) {
            Optional<MachineInstance.CaptureSummary> live = instance.captureResult();
            if (live.isPresent()) {
                return live;
            }
        }
        return Optional.ofNullable(lastCapture);
    }
}
