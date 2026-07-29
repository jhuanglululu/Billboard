package com.jhuanglululu.billboard.scheduler;

import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.render.Origin;
import com.jhuanglululu.billboard.render.PacketEventsRenderer;
import com.jhuanglululu.billboard.runtime.AnimationInstance;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.billboard.stats.StatsSource;
import com.jhuanglululu.wasmachine.runtime.LogSink;
import com.jhuanglululu.billboard.runtime.TickResult;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final PacketEventsRenderer renderer;
    private final Queue<String> logBuffer = new ConcurrentLinkedQueue<>();

    private AnimationInstance instance;

    // What the interpreter last reported, kept so an instance that dies (or is released with its
    // entities standing) can still be part of a capture report instead of vanishing from it.
    private MachineInstance.StatsSnapshot lastSnapshot;
    private MachineInstance.CaptureSummary lastCapture;
    private int lastLiveEntities;
    private int lastTotalSpawns;
    private int restarts;

    public RunningInstance(Placement placement, String ownerLabel, Module module,
            BlockStateValidator validator, ContentValidator content, long memoryCapBytes) {
        this.placement = placement;
        this.ownerLabel = ownerLabel;
        this.module = module;
        this.validator = validator;
        this.content = content;
        this.memoryCapBytes = memoryCapBytes;
        this.renderer = new PacketEventsRenderer(
                new Origin(placement.world(), placement.x(), placement.y(), placement.z()));
        this.instance = build();
    }

    private AnimationInstance build() {
        LogSink sink = (animation, message) -> logBuffer.add(message);
        // The seed is stable across restarts and across restarts of this placement, so a
        // per_player billboard shows each player the same variation on every visit.
        long seed = AnimationInstance.stableSeed(placement.animation(), placement.id(), ownerLabel);
        return new AnimationInstance(placement.animation(), module, renderer, validator, content,
                sink, memoryCapBytes, seed);
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

    /** Restart from scratch (ExitCode.Repeat): clear entities and rebuild the interpreter. */
    public void restart() {
        remember();
        restarts++;
        renderer.destroyAll();
        instance = build();
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
        lastTotalSpawns = instance.registry().allSpawned().size();
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
        return MessageFormats.EVERYONE.equals(ownerLabel) ? placement.id() : ownerLabel;
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
    public int totalEntitySpawns() {
        return instance != null ? instance.registry().allSpawned().size() : lastTotalSpawns;
    }

    @Override
    public int restarts() {
        return restarts;
    }

    @Override
    public boolean startCapture(int ticks) {
        return instance != null && instance.startCapture(ticks);
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
