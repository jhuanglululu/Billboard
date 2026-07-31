package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param entities     what the window saw of entity counts, sampled tick by tick
 */
public record CaptureReport(String target, long windowTicks, long elapsedTicks, boolean stopped,
        List<InstanceStats> instances, EntitySamples entities) {

    public CaptureReport {
        instances = List.copyOf(instances);
    }

    /**
     * Entity counts as the window saw them. The engine counts instructions and bytes but knows
     * nothing of the entities an instance has standing, so Billboard samples them itself, once a
     * tick, summed over the armed instances — the same "per-tick figure is the sum over instances"
     * convention the instruction and memory figures follow.
     *
     * @param sum   every per-tick sum added up
     * @param ticks how many ticks contributed one
     * @param peak  the largest single-tick sum
     */
    public record EntitySamples(long sum, long ticks, int peak) {

        /** A window that sampled nothing: no instance was ever armed on it. */
        public static final EntitySamples NONE = new EntitySamples(0, 0, 0);

        /** Entities standing at once, averaged over the ticks that were sampled. */
        public double mean() {
            return ticks == 0 ? 0 : (double) sum / ticks;
        }
    }

    /**
     * One instance's contribution.
     *
     * @param label        how to name this instance: {@code animation/id:owner}, where owner is
     *                     the viewing player for {@code per_player} and {@code EVERYONE} otherwise
     * @param placement    the placement key alone ({@code animation/id}), for counting how many
     *                     placements the window touched whoever owned the instances
     * @param capture      what its window saw; a window that took no samples is still reported
     * @param snapshot     its live gauges as of the end of the window
     * @param liveEntities entities standing at the end of the window
     */
    public record InstanceStats(String label, String placement, CaptureSummary capture,
            StatsSnapshot snapshot, int liveEntities) {

        /** Whether this instance produced any sample at all. */
        public boolean sampled() {
            return capture.ticksCaptured() > 0;
        }
    }

    /**
     * Every capture window one placement-owner pair produced, merged into the single block the
     * report prints. An instance that dies and is restarted inside the window leaves one engine
     * window per run; printed separately they read as duplicate rows, so the report merges by
     * label and keeps the arithmetic here where it is testable.
     *
     * @param runs the windows in arming order — the last one is the newest, whose live gauges
     *             (tasks, entities, memory cap) describe the placement as it stands now
     */
    public record MergedInstance(List<InstanceStats> runs) {

        public MergedInstance {
            runs = List.copyOf(runs);
        }

        public String label() {
            return runs.getFirst().label();
        }

        /** How many instances (engine windows) this block covers. */
        public int instanceCount() {
            return runs.size();
        }

        public long activeTicks() {
            return runs.stream().mapToLong(r -> r.capture().activeTicks()).sum();
        }

        public long capturedTicks() {
            return runs.stream().mapToLong(r -> r.capture().ticksCaptured()).sum();
        }

        public boolean sampled() {
            return capturedTicks() > 0;
        }

        /** Mean over every captured tick of every run — weighted, not a mean of means. */
        public double meanInstructions() {
            long ticks = capturedTicks();
            return ticks == 0 ? 0
                    : (double) runs.stream().mapToLong(r -> r.capture().instructionsSum()).sum() / ticks;
        }

        public long instructionsMin() {
            return runs.stream().filter(InstanceStats::sampled)
                    .mapToLong(r -> r.capture().instructionsMin()).min().orElse(0);
        }

        public long instructionsMax() {
            return runs.stream().mapToLong(r -> r.capture().instructionsMax()).max().orElse(0);
        }

        public long memoryPeakBytes() {
            return runs.stream().mapToLong(r -> r.capture().memoryPeakBytes()).max().orElse(0);
        }

        /** The newest run: the placement as it stands at the end of the window. */
        public InstanceStats newest() {
            return runs.getLast();
        }
    }

    /** The instances grouped by label in arming order: one entry per placement-owner pair. */
    public List<MergedInstance> merged() {
        Map<String, List<InstanceStats>> groups = new LinkedHashMap<>();
        for (InstanceStats i : instances) {
            groups.computeIfAbsent(i.label(), k -> new ArrayList<>()).add(i);
        }
        return groups.values().stream().map(MergedInstance::new).toList();
    }

    /** Distinct placements the window touched, whoever owned their instances. */
    public int placements() {
        return (int) instances.stream().map(InstanceStats::placement).distinct().count();
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
