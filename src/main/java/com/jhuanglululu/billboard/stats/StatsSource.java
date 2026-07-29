package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import java.util.Optional;

/**
 * What the capture orchestrator needs of one live instance: who it is, what it measures, and how
 * to arm it. Implemented by the scheduler's running instances; tests supply fakes, which is why
 * the orchestrator depends on this rather than on the Bukkit-bound instance class.
 *
 * <p>Every reader is {@link Optional} because an instance can outlive its interpreter — an
 * animation that exited {@code Keep} still has entities standing but nothing left to measure —
 * and because a capture that ended with the instance is served from what was remembered at its
 * death, not from a live engine.
 */
public interface StatsSource {

    /** The animation name. */
    String animation();

    /** The placement id this instance belongs to. */
    String placementId();

    /**
     * How a report should name this instance: the owning player for a {@code per_player}
     * instance, the placement id for a shared one. The convention lives with the implementation
     * because only it knows which of the two it is.
     */
    String label();

    /** Instant gauges and run totals, or empty once the interpreter is gone. */
    Optional<MachineInstance.StatsSnapshot> stats();

    /** Entities this instance has standing right now. */
    int liveEntities();

    /**
     * The tick this instance's current run began on, so a report can derive its age. Uptime is
     * Billboard's to know: the engine keeps no counter for it, and the scheduler already stamps
     * every instance it starts.
     */
    long startTick();

    /**
     * Arms a capture over the next {@code ticks} ticks.
     *
     * @return false if this instance cannot be armed — no interpreter, or one already armed
     */
    boolean startCapture(int ticks);

    /**
     * Closes an armed capture early, keeping its samples.
     *
     * @return false if nothing was armed on this instance
     */
    boolean stopCapture();

    /** The most recent finished capture, including one closed early by the instance ending. */
    Optional<MachineInstance.CaptureSummary> captureResult();
}
