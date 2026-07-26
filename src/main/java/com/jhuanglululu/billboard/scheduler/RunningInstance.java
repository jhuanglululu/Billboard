package com.jhuanglululu.billboard.scheduler;

import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.render.Origin;
import com.jhuanglululu.billboard.render.PacketEventsRenderer;
import com.jhuanglululu.billboard.runtime.AnimationInstance;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.billboard.runtime.LogSink;
import com.jhuanglululu.billboard.runtime.TickResult;
import com.jhuanglululu.wasm.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.entity.Player;

/**
 * One live animation instance and its packet renderer, plus the buffering that keeps guest
 * {@code log} output off the worker thread. Ticked on a worker by {@link AnimationScheduler};
 * viewer changes and cleanup happen on the main thread.
 */
public final class RunningInstance {

    private final Placement placement;
    private final String ownerLabel;
    private final Module module;
    private final BlockStateValidator validator;
    private final ContentValidator content;
    private final long memoryCapBytes;
    private final PacketEventsRenderer renderer;
    private final Queue<String> logBuffer = new ConcurrentLinkedQueue<>();

    private AnimationInstance instance;

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
        renderer.destroyAll();
        instance = build();
    }

    /** Release the interpreter but keep the rendered entities visible (ExitCode.Keep). */
    public void releaseRuntimeKeepEntities() {
        instance = null;
    }

    /** Stop and clean up: despawn every entity this instance ever spawned. */
    public void stop() {
        if (instance != null) {
            instance.cleanup();
            instance = null;
        }
        renderer.destroyAll();
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
}
