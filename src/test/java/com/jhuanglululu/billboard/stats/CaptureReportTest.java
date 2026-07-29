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

    private static StatsSnapshot snapshot(long runPeakBytes, long totalInstructions) {
        return new StatsSnapshot(0, 0, runPeakBytes, 16 * MIB, 1, 0, 100, totalInstructions, 0);
    }

    /**
     * Three instances of one animation, with numbers chosen so every aggregate has a known answer:
     * A averages 1000/4 = 250 instr/tick over 4 MiB-sized samples, B averages 200/2 = 100 over
     * samples of 1 and 2 MiB, and C never ran a tick.
     */
    @Test
    void aggregatesAcrossInstancesWithHandComputedAnswers() {
        CaptureSummary a = new CaptureSummary(4, true, 100, 400, 1000, 4 * MIB, MIB);
        CaptureSummary b = new CaptureSummary(2, false, 50, 150, 200, 3 * MIB, 2 * MIB);
        CaptureSummary none = new CaptureSummary(0, false, 0, 0, 0, 0, 0);

        CaptureReport report = new CaptureReport("demo", 80, List.of(
                new CaptureReport.InstanceStats("alice", a, snapshot(2 * MIB, 9000), 3, 5, 0),
                new CaptureReport.InstanceStats("bob", b, snapshot(5 * MIB, 400), 1, 1, 2),
                new CaptureReport.InstanceStats("spot3", none, snapshot(0, 0), 0, 0, 0)));

        assertTrue(report.anySamples());
        assertEquals(2, report.sampledInstances());
        assertTrue(report.partial(), "b ended early and c never ran: the window is partial");

        // 250 + 100 + 0 — the per-tick cost of the animation is what its instances add up to,
        // not what they average, because they all pay it in the same tick.
        assertEquals(350.0, report.meanInstructionsPerTick());
        assertEquals(1200L, report.windowInstructions());
        // 1 MiB (4 MiB over 4 samples) + 1.5 MiB (3 MiB over 2 samples)
        assertEquals(2.5 * MIB, report.meanMemoryBytes());
        // the highest run watermark, not the highest window sample
        assertEquals(5 * MIB, report.peakMemoryBytes());
        assertEquals(4, report.liveEntities());
    }

    @Test
    void aWindowThatSampledNothingReportsNothingRatherThanZeroes() {
        CaptureSummary none = new CaptureSummary(0, false, 0, 0, 0, 0, 0);
        CaptureReport report = new CaptureReport("demo", 200, List.of(
                new CaptureReport.InstanceStats("spot1", none, snapshot(0, 0), 0, 0, 0)));

        assertFalse(report.anySamples());
        assertEquals(0, report.sampledInstances());
        assertEquals(0.0, report.meanInstructionsPerTick());
    }
}
