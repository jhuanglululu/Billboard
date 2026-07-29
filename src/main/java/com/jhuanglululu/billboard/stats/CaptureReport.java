package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.List;

/**
 * What one finished capture window saw, across every instance of the target. Pure arithmetic over
 * the engine's raw summaries — no formatting, no Bukkit — so the cross-instance math is testable
 * with hand-built numbers.
 *
 * <p><b>Why the aggregates sum per-instance means.</b> The question this command answers is "what
 * is this animation costing the server each tick", and every instance of it pays its own cost in
 * the same tick. Summing means therefore gives instructions per tick <em>for the animation</em>;
 * averaging them would flatter an animation that runs in twenty copies.
 *
 * @param target       what was captured, as the user named it
 * @param windowTicks  the length the capture was armed for
 * @param elapsedTicks how long it actually ran — shorter than {@code windowTicks} only when it
 *                     was stopped early
 * @param stopped      whether someone ended the window before its deadline
 * @param instances    one entry per instance that was armed, in the order they were armed
 */
public record CaptureReport(String target, long windowTicks, long elapsedTicks, boolean stopped,
        List<InstanceStats> instances) {

    public CaptureReport {
        instances = List.copyOf(instances);
    }

    /**
     * One instance's contribution.
     *
     * @param label        how to name this instance: the owner for {@code per_player}, otherwise
     *                     the placement id
     * @param capture      what its window saw; a window that took no samples is still reported
     * @param snapshot     its live gauges as of the end of the window
     * @param liveEntities entities standing at the end of the window
     * @param uptimeTicks  ticks since this run began, derived from the scheduler's start stamp
     */
    public record InstanceStats(String label, CaptureSummary capture, StatsSnapshot snapshot,
            int liveEntities, long uptimeTicks) {

        /** Whether this instance produced any sample at all. */
        public boolean sampled() {
            return capture.ticksCaptured() > 0;
        }
    }

    /** Whether any instance produced a sample; false means the report has nothing to show. */
    public boolean anySamples() {
        return instances.stream().anyMatch(InstanceStats::sampled);
    }

    /** Instances that produced at least one sample. */
    public int sampledInstances() {
        return (int) instances.stream().filter(InstanceStats::sampled).count();
    }

    /** Whether any armed instance's window ended early (it died, or started mid-window). */
    public boolean partial() {
        return instances.stream().anyMatch(i -> !i.capture().complete());
    }

    /** Instructions per tick the whole target cost: the per-instance means added up. */
    public double meanInstructionsPerTick() {
        return instances.stream().mapToDouble(i -> i.capture().meanInstructions()).sum();
    }

    /** Every instruction the target spent inside the window. */
    public long windowInstructions() {
        return instances.stream().mapToLong(i -> i.capture().instructionsSum()).sum();
    }

    /** Mean bytes charged across all instances at once, over the window. */
    public double meanMemoryBytes() {
        return instances.stream().mapToDouble(i -> i.capture().meanMemoryBytes()).sum();
    }

    /**
     * The instances' window peaks added up. Nothing outside a capture is measured any more, so a
     * sampled end-of-tick peak is the only peak there is — and like the mean it is summed, because
     * the question is what the animation costs. It over-states if the peaks did not fall on the
     * same tick, which is the honest direction for a ceiling.
     */
    public long peakMemoryBytes() {
        return instances.stream().mapToLong(i -> i.capture().memoryPeakBytes()).sum();
    }

    /** Live entities across every instance at the end of the window. */
    public int liveEntities() {
        return instances.stream().mapToInt(InstanceStats::liveEntities).sum();
    }
}
