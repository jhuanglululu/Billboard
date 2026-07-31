package com.jhuanglululu.billboard.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureReportTest {

    private static final long MIB = 1024 * 1024;

    private static StatsSnapshot snapshot(long usedBytes, int liveTasks) {
        return new StatsSnapshot(usedBytes, 16 * MIB, liveTasks, 0);
    }

    /**
     * Three instances of one animation, with numbers chosen so every aggregate has a known answer:
     * A averages 1000/4 = 250 instr/tick over 4 MiB-sized samples, B averages 200/2 = 100 over
     * samples of 1 and 2 MiB, and C never ran a tick.
     */
    @Test
    void aggregatesAcrossInstancesWithHandComputedAnswers() {
        CaptureSummary a = new CaptureSummary(4, 3, true, 100, 400, 1000, 4 * MIB, MIB);
        CaptureSummary b = new CaptureSummary(2, 2, false, 50, 150, 200, 3 * MIB, 2 * MIB);
        CaptureSummary none = new CaptureSummary(0, 0, false, 0, 0, 0, 0, 0);

        CaptureReport report = new CaptureReport("demo", 80, 80, false, List.of(
                new CaptureReport.InstanceStats("alice", "demo/spot1", a, snapshot(2 * MIB, 3), 3),
                new CaptureReport.InstanceStats("bob", "demo/spot1", b, snapshot(5 * MIB, 1), 1),
                new CaptureReport.InstanceStats("spot3", "demo/spot3", none, snapshot(0, 0), 0)),
                new CaptureReport.EntitySamples(30, 4, 12));

        assertTrue(report.anySamples());
        assertEquals(2, report.sampledInstances());
        assertTrue(report.partial(), "b ended early and c never ran: the window is partial");

        // 250 + 100 + 0 — the per-tick cost of the animation is what its instances add up to,
        // not what they average, because they all pay it in the same tick.
        assertEquals(350.0, report.meanInstructionsPerTick());
        assertEquals(1200L, report.windowInstructions());
        // 1 MiB (4 MiB over 4 samples) + 1.5 MiB (3 MiB over 2 samples)
        assertEquals(2.5 * MIB, report.meanMemoryBytes());
        // 1 MiB + 2 MiB: the window peaks added up, the only peaks that still exist
        assertEquals(3 * MIB, report.peakMemoryBytes());
        assertEquals(4, report.liveEntities());
        // 30 entities seen over 4 sampled ticks: the mean is per tick, not per instance
        assertEquals(7.5, report.entities().mean());
        assertEquals(12, report.entities().peak());
    }

    /**
     * A restart mid-window leaves two engine windows under one label; the report merges them.
     * Numbers hand-picked: run 1 captured 139 ticks (120 active, 100–400 instr, sum 27,800),
     * run 2 captured 44 (40 active, 50–150 instr, sum 8,800) — merged mean is the weighted
     * 36,600/183 = 200, not the 158.6 a mean-of-means would give.
     */
    @Test
    void runsOfTheSameLabelMergeIntoOneBlockWithWeightedFigures() {
        CaptureSummary run1 = new CaptureSummary(139, 120, false, 100, 400, 27_800, 139 * MIB, 2 * MIB);
        CaptureSummary run2 = new CaptureSummary(44, 40, false, 50, 150, 8_800, 44 * MIB, MIB);
        CaptureSummary other = new CaptureSummary(200, 200, true, 10, 20, 3_000, 200 * MIB, MIB);

        CaptureReport report = new CaptureReport("demo", 200, 200, false, List.of(
                new CaptureReport.InstanceStats("demo/test:EVERYONE", "demo/test", run1, snapshot(0, 0), 1),
                new CaptureReport.InstanceStats("demo/demo-2:alice", "demo/demo-2", other, snapshot(MIB, 1), 5),
                new CaptureReport.InstanceStats("demo/test:EVERYONE", "demo/test", run2, snapshot(2 * MIB, 1), 23)),
                CaptureReport.EntitySamples.NONE);

        assertEquals(2, report.merged().size());
        assertEquals(2, report.placements());

        CaptureReport.MergedInstance merged = report.merged().getFirst();
        assertEquals("demo/test:EVERYONE", merged.label());
        assertEquals(2, merged.instanceCount());
        assertEquals(160L, merged.activeTicks());
        assertEquals(183L, merged.capturedTicks());
        assertEquals(200.0, merged.meanInstructions());
        assertEquals(50L, merged.instructionsMin());
        assertEquals(400L, merged.instructionsMax());
        assertEquals(2 * MIB, merged.memoryPeakBytes());
        // The newest run carries the live gauges: the placement as it stands now.
        assertEquals(23, merged.newest().liveEntities());

        CaptureReport.MergedInstance single = report.merged().getLast();
        assertEquals("demo/demo-2:alice", single.label());
        assertEquals(1, single.instanceCount());
    }

    /**
     * A run that never ticked must not drag the merged minimum to its placeholder zero.
     */
    @Test
    void anUnsampledRunDoesNotPolluteTheMergedMinimum() {
        CaptureSummary ran = new CaptureSummary(10, 10, false, 70, 90, 800, 10 * MIB, MIB);
        CaptureSummary never = new CaptureSummary(0, 0, false, 0, 0, 0, 0, 0);
        CaptureReport.MergedInstance merged = new CaptureReport.MergedInstance(List.of(
                new CaptureReport.InstanceStats("demo/spot1:EVERYONE", "demo/spot1", ran, snapshot(0, 0), 0),
                new CaptureReport.InstanceStats("demo/spot1:EVERYONE", "demo/spot1", never, snapshot(0, 0), 0)));

        assertEquals(70L, merged.instructionsMin());
        assertEquals(80.0, merged.meanInstructions());
        assertTrue(merged.sampled());
    }

    @Test
    void aWindowThatSampledNothingReportsNothingRatherThanZeroes() {
        CaptureSummary none = new CaptureSummary(0, 0, false, 0, 0, 0, 0, 0);
        CaptureReport report = new CaptureReport("demo", 200, 200, false, List.of(
                new CaptureReport.InstanceStats("spot1", "demo/spot1", none, snapshot(0, 0), 0)),
                CaptureReport.EntitySamples.NONE);

        assertFalse(report.anySamples());
        assertEquals(0, report.sampledInstances());
        assertEquals(0.0, report.meanInstructionsPerTick());
        assertEquals(0.0, report.entities().mean(), "no sampled tick means no division by zero");
    }
}
