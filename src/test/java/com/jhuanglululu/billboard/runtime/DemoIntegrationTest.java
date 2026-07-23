package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.runtime.RecordingRenderer.Event;
import com.jhuanglululu.wasm.Module;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The milestone: run the real {@code demo.wasm} to completion through {@link AnimationInstance},
 * recording every renderer call and log, and assert the expectations hand-computed from
 * {@code billboard-rs/demo/src/lib.rs}.
 *
 * <h2>Timeline (ticks), computed by hand from the demo source</h2>
 * <pre>
 * t=0    log "assembling"; spawn 15 panels (ids 1..15, gray_concrete); sleep 2.5s = 50t.
 * t=50   spawn lamp (id 16, sea_lantern); state() checkpoint; fork the pulse task (task 1);
 *        marquee begins. Pulse task (from t=50) does 6 cycles of
 *        {scale 1.5 over5; sleep6; scale 1.0 over5; sleep6} = 12t/cycle -> ends t=122.
 *        Marquee: spawn runner (id 17); 4 passes of {move15; sleep15; set_block} -> t=110,
 *        then runner.despawn() and join(pulse).
 * t=122  pulse ends; join returns. lamp.scale() (=1.0); log "scale before restore";
 *        lamp.set(resting) -> setPosition/Rotation/Scale/Block burst (over 0); log "finale";
 *        finale leans the 15 panels with a sleep(2) after i = 0,3,6,9,12:
 *        t=122(i0) 124(i3) 126(i6) 128(i9) 130(i12); i13,14 at t=132, then sleep 1s = 20t.
 * t=152  main returns ExitCode::End -> Finished(END).
 * </pre>
 * So the animation ends at tick 152; ticks 0..152 inclusive = 153 tick() calls.
 */
class DemoIntegrationTest {

    private static final int LAMP_ID = 16;
    private static final int RUNNER_ID = 17;
    private static final long EXPECTED_FINISH_TICK = 152;
    private static final int EXPECTED_SPAWNS = 17;

    private static byte[] loadDemo() throws IOException {
        try (InputStream in = DemoIntegrationTest.class.getResourceAsStream("/demo.wasm")) {
            assertNotNull(in, "demo.wasm fixture is missing");
            return in.readAllBytes();
        }
    }

    @Test
    void demoRunsToCompletionWithExpectedEffects() throws IOException {
        RecordingRenderer renderer = new RecordingRenderer();
        List<String> logs = new ArrayList<>();

        AnimationInstance inst = new AnimationInstance(
                "demo",
                Module.parse(loadDemo()),
                renderer,
                blockState -> true, // accepting validator
                (name, message) -> logs.add(message),
                16L << 20); // 16 MiB memory cap

        long finishTick = -1;
        TickResult result = null;
        for (long t = 0; t <= 10_000; t++) {
            result = inst.tick(t, 5_000_000);
            if (!(result instanceof TickResult.Running)) {
                finishTick = t;
                break;
            }
        }
        inst.cleanup();

        // (a) result and timeline
        assertInstanceOf(TickResult.Finished.class, result);
        assertEquals(ExitCode.END, ((TickResult.Finished) result).exitCode());
        assertEquals(EXPECTED_FINISH_TICK, finishTick, "hand-computed finish tick");

        // (b) exactly 17 spawns: 15 panels + lamp + runner, with the expected blocks/ids.
        List<Event> spawns = eventsOfKind(renderer, "spawn");
        assertEquals(EXPECTED_SPAWNS, spawns.size());
        for (int i = 0; i < 15; i++) {
            assertEquals(i + 1, spawns.get(i).id());
            assertEquals("minecraft:gray_concrete", spawns.get(i).block());
        }
        assertEquals(LAMP_ID, spawns.get(15).id());
        assertEquals("minecraft:sea_lantern", spawns.get(15).block());
        assertEquals(RUNNER_ID, spawns.get(16).id());
        assertEquals("minecraft:red_concrete", spawns.get(16).block());
        // Panel column x = (col - 2) via i64->f64 signed conversion; first column is at -2.
        assertArrayCloseTo(new double[] {-2.0, -1.0, 0.0}, spawns.get(0).nums());
        assertArrayCloseTo(new double[] {0.0, 4.0, 0.4}, spawns.get(15).nums()); // lamp spawn pos

        // (c) the pulse task's 6 cycles: setScale on the lamp, over=5, alternating 1.5 / 1.0.
        List<Event> pulse = renderer.events.stream()
                .filter(e -> e.kind().equals("setScale") && e.id() == LAMP_ID && e.over() == 5)
                .collect(Collectors.toList());
        assertEquals(12, pulse.size());
        for (int i = 0; i < 12; i++) {
            double expected = (i % 2 == 0) ? 1.5 : 1.0;
            assertEquals(expected, pulse.get(i).nums()[0], 1e-9);
            assertEquals(expected, pulse.get(i).nums()[1], 1e-9);
            assertEquals(expected, pulse.get(i).nums()[2], 1e-9);
        }

        // (d) runner despawned before the finale (before the lamp restore burst).
        int runnerDespawn = indexOf(renderer, e -> e.kind().equals("despawn") && e.id() == RUNNER_ID);
        int restorePosition = indexOf(renderer, e -> e.kind().equals("setPosition")
                && e.id() == LAMP_ID && e.over() == 0);
        assertTrue(runnerDespawn >= 0, "runner was despawned");
        assertTrue(restorePosition >= 0, "lamp restore burst present");
        assertTrue(runnerDespawn < restorePosition, "runner despawned before the finale restore");

        // (e) lamp restore burst (set(&resting)): position/rotation/scale/block, all over=0,
        //     back to the spawn checkpoint (pos (0,4,0.4), identity rotation, scale 1, sea_lantern).
        Event rp = renderer.events.get(restorePosition);
        assertArrayCloseTo(new double[] {0.0, 4.0, 0.4}, rp.nums());
        Event rr = renderer.events.get(indexOf(renderer, e -> e.kind().equals("setRotation")
                && e.id() == LAMP_ID && e.over() == 0));
        assertArrayCloseTo(new double[] {0.0, 0.0, 0.0, 1.0}, rr.nums());
        Event rs = lastEvent(renderer, e -> e.kind().equals("setScale") && e.id() == LAMP_ID && e.over() == 0);
        assertArrayCloseTo(new double[] {1.0, 1.0, 1.0}, rs.nums());
        Event rb = lastEvent(renderer, e -> e.kind().equals("setBlock") && e.id() == LAMP_ID);
        assertEquals("minecraft:sea_lantern", rb.block());

        // (f) the three named logs appear in order (a scale-report log also appears between
        //     the second and third — asserted present, not counted).
        assertOrderedSubsequence(logs,
                "demo: assembling panel", "demo: pulse task running", "demo: finale");
        assertTrue(logs.stream().anyMatch(l -> l.contains("lamp scale before restore")),
                "the format! scale log is present");

        // (g) after cleanup, every spawned id was despawned exactly once.
        List<Event> despawns = eventsOfKind(renderer, "despawn");
        assertEquals(EXPECTED_SPAWNS, despawns.size());
        for (int id = 1; id <= EXPECTED_SPAWNS; id++) {
            final int target = id;
            assertEquals(1L, despawns.stream().filter(e -> e.id() == target).count(),
                    "id " + id + " despawned exactly once");
        }
    }

    // --- helpers ---

    private static List<Event> eventsOfKind(RecordingRenderer r, String kind) {
        return r.events.stream().filter(e -> e.kind().equals(kind)).collect(Collectors.toList());
    }

    private static int indexOf(RecordingRenderer r, java.util.function.Predicate<Event> p) {
        for (int i = 0; i < r.events.size(); i++) {
            if (p.test(r.events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Event lastEvent(RecordingRenderer r, java.util.function.Predicate<Event> p) {
        Event found = null;
        for (Event e : r.events) {
            if (p.test(e)) {
                found = e;
            }
        }
        assertNotNull(found, "expected a matching event");
        return found;
    }

    private static void assertArrayCloseTo(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9);
        }
    }

    private static void assertOrderedSubsequence(List<String> logs, String... needles) {
        int idx = 0;
        for (String log : logs) {
            if (idx < needles.length && log.equals(needles[idx])) {
                idx++;
            }
        }
        assertEquals(needles.length, idx,
                "expected logs " + List.of(needles) + " in order within " + logs);
    }
}
