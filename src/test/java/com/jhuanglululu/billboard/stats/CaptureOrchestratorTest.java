package com.jhuanglululu.billboard.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaptureOrchestratorTest {

    /** A stand-in instance that records what window it was armed with. */
    private static final class FakeSource implements StatsSource {
        private final String animation;
        private final String placementId;
        Integer armedFor;               // the ticks it was armed with; null = never armed
        CaptureSummary result;

        FakeSource(String animation, String placementId) {
            this.animation = animation;
            this.placementId = placementId;
        }

        @Override
        public String animation() {
            return animation;
        }

        @Override
        public String placementId() {
            return placementId;
        }

        @Override
        public String label() {
            return placementId;
        }

        @Override
        public Optional<StatsSnapshot> stats() {
            return Optional.of(new StatsSnapshot(0, 0, 0, 0, 1, 0, 0, 0, 0));
        }

        @Override
        public int liveEntities() {
            return 0;
        }

        @Override
        public int totalEntitySpawns() {
            return 0;
        }

        @Override
        public int restarts() {
            return 0;
        }

        @Override
        public boolean startCapture(int ticks) {
            if (armedFor != null) {
                return false;   // the engine refuses a second window, and so does this
            }
            armedFor = ticks;
            return true;
        }

        @Override
        public Optional<CaptureSummary> captureResult() {
            return Optional.ofNullable(result);
        }
    }

    private static final CaptureSummary SAMPLED =
            new CaptureSummary(30, true, 10, 40, 600, 30, 2);

    @Test
    void aTargetWithNoInstancesStillArmsAndThenReportsNoSamples() {
        CaptureOrchestrator orchestrator = new CaptureOrchestrator();
        List<CaptureReport> reports = new ArrayList<>();

        CaptureOrchestrator.CaptureStart start = orchestrator.start(
                "demo", "demo", null, 40, 0, List.of(), reports::add);

        assertTrue(start.started());
        assertEquals(0, start.armed(), "nothing is running it — the warning case");

        for (long tick = 1; tick <= 40; tick++) {
            orchestrator.tick(tick, List.of());
        }
        assertTrue(reports.isEmpty(), "the window must not close before its deadline");

        orchestrator.tick(41, List.of());
        assertEquals(1, reports.size());
        assertFalse(reports.get(0).anySamples());
        assertTrue(reports.get(0).instances().isEmpty());
        assertTrue(orchestrator.idle(), "a delivered window stops costing anything");
    }

    @Test
    void anInstanceStartingMidWindowIsArmedForWhatIsLeftAndOnlyOnce() {
        CaptureOrchestrator orchestrator = new CaptureOrchestrator();
        List<CaptureReport> reports = new ArrayList<>();
        FakeSource early = new FakeSource("demo", "spot1");
        List<FakeSource> live = new ArrayList<>(List.of(early));

        orchestrator.start("demo", "demo", null, 40, 0, live, reports::add);
        assertEquals(40, early.armedFor);

        // A second instance appears a quarter of the way in: it should cover the rest of the
        // window and finish with the first, not run 40 ticks past the report.
        FakeSource late = new FakeSource("demo", "spot2");
        live.add(late);
        orchestrator.tick(10, live);
        assertEquals(30, late.armedFor, "40-tick window armed at 0, joined at 10");

        orchestrator.tick(11, live);
        orchestrator.tick(12, live);
        early.result = SAMPLED;
        late.result = SAMPLED;
        orchestrator.tick(41, live);

        assertEquals(1, reports.size());
        assertEquals(List.of("spot1", "spot2"),
                reports.get(0).instances().stream().map(CaptureReport.InstanceStats::label).toList(),
                "each instance appears once, in arming order");
    }

    @Test
    void anInstanceOfAnotherAnimationIsNeverArmed() {
        CaptureOrchestrator orchestrator = new CaptureOrchestrator();
        FakeSource other = new FakeSource("clock", "lobby");
        FakeSource mine = new FakeSource("demo", "spot1");

        orchestrator.start("demo", "demo", null, 40, 0, List.of(other, mine), report -> { });

        assertEquals(40, mine.armedFor);
        assertNull(other.armedFor);
    }

    @Test
    void narrowingToOnePlacementLeavesTheAnimationsOtherPlacementsAlone() {
        CaptureOrchestrator orchestrator = new CaptureOrchestrator();
        FakeSource one = new FakeSource("demo", "spot1");
        FakeSource two = new FakeSource("demo", "spot2");

        orchestrator.start("spot1", "demo", "spot1", 40, 0, List.of(one, two), report -> { });

        assertEquals(40, one.armedFor);
        assertNull(two.armedFor);
    }

    @Test
    void aSecondRequestReportsTheRemainingTimeInsteadOfRestartingTheWindow() {
        CaptureOrchestrator orchestrator = new CaptureOrchestrator();
        List<CaptureReport> reports = new ArrayList<>();
        FakeSource source = new FakeSource("demo", "spot1");

        orchestrator.start("demo", "demo", null, 200, 0, List.of(source), reports::add);
        CaptureOrchestrator.CaptureStart second = orchestrator.start(
                "demo", "demo", null, 200, 50, List.of(source), reports::add);

        assertFalse(second.started());
        assertEquals(150, second.remainingTicks());
        assertEquals(200, source.armedFor, "the running window keeps the length it was armed with");

        source.result = SAMPLED;
        orchestrator.tick(201, List.of(source));
        assertEquals(1, reports.size(), "one window, one report — the refused request added none");
    }
}
