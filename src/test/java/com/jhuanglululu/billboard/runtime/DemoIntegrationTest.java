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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The milestone: run the real v2 {@code demo.wasm} to completion through {@link AnimationInstance},
 * recording every renderer call and log, and assert the story it is choreographed to tell. The
 * fixture is the SDK's worked example, so every expectation here is derived from
 * {@code billboard-rs/demo/src/lib.rs} — its header tabulates the timing and its sections are
 * lettered A..O.
 *
 * <h2>What the demo spawns (ids are handed out in spawn order, from 1)</h2>
 * <pre>
 *  1..15  panel        5 columns x 3 rows of gray_concrete            (section A)
 * 16..20  trim         oak stairs (facing/half), lit furnace,
 *                      repeater[delay=3], oak_log[axis=y]             (section A)
 * 24..39  strip        16 palette tiles at x = -7.5 .. 7.5            (section B)
 *     40  lamp         sea_lantern, the checkpoint subject            (section D/M)
 *     41  headline     text display, typewriter then marquee          (section E/N)
 *     42  sword        item display, keyframed timeline               (section I)
 *     43  usher        armor stand, arm waves + turn                  (section K)
 *     44  emerald      item entity, host-tweened position             (section J)
 *     45  runner       block display driven off a channel             (section J)
 * 47..51  logo         5-member group: orbit + scale pulse            (section G/H)
 * 41/46/52             the three text displays
 * </pre>
 *
 * <p>Total scripted duration is 603 ticks, so the run ends on tick 603 with
 * {@link ExitCode#END}. Because the demo sets {@code random_seed} and never draws from the
 * non-deterministic stream, the whole host-call trace is reproducible — which is what
 * {@link #traceIsIdenticalAcrossRuns()} pins down, and the property that makes this a usable
 * regression fixture at all.
 */
class DemoIntegrationTest {

    private static final long EXPECTED_FINISH_TICK = 603;

    /** Entity ids, from the table above. */
    private static final int STRIP_FIRST = 24;
    private static final int STRIP_LAST = 39;
    private static final int LAMP_ID = 40;
    private static final int HEADLINE_ID = 41;
    private static final int SWORD_ID = 42;
    private static final int USHER_ID = 43;
    private static final int EMERALD_ID = 44;
    private static final int LOGO_FIRST = 47;
    private static final int LOGO_LAST = 51;
    private static final int TOTAL_ENTITIES = 52;

    /** Section F sweeps the gradient across every tile for 30 frames. */
    private static final int SWEEP_FRAMES = 30;

    private record Run(TickResult result, long finishTick, RecordingRenderer renderer,
            List<String> logs, long nonDeterministicDraws) {}

    private static byte[] loadDemo() throws IOException {
        try (InputStream in = DemoIntegrationTest.class.getResourceAsStream("/demo.wasm")) {
            assertNotNull(in, "demo.wasm fixture is missing");
            return in.readAllBytes();
        }
    }

    /** Drives the demo tick by tick to completion, then cleans up like the scheduler would. */
    private static Run run() throws IOException {
        return run(50_000_000);
    }

    private static Run run(long fuelBudget) throws IOException {
        RecordingRenderer renderer = new RecordingRenderer();
        List<String> logs = new ArrayList<>();
        AnimationInstance inst = new AnimationInstance("demo", Module.parse(loadDemo()), renderer,
                blockState -> true, ContentValidator.PERMISSIVE, (name, message) -> logs.add(message),
                16L << 20, 0L);

        long finishTick = -1;
        TickResult result = null;
        for (long t = 0; t <= 5_000; t++) {
            result = inst.tick(t, fuelBudget);
            if (!(result instanceof TickResult.Running)) {
                finishTick = t;
                break;
            }
        }
        inst.cleanup();
        return new Run(result, finishTick, renderer, logs, inst.nonDeterministicDraws());
    }

    @Test
    void demoRunsTheWholeShowToItsScriptedEnd() throws IOException {
        Run run = run();

        assertInstanceOf(TickResult.Finished.class, run.result());
        assertEquals(ExitCode.END, ((TickResult.Finished) run.result()).exitCode());
        assertEquals(EXPECTED_FINISH_TICK, run.finishTick(),
                "the demo's own header tabulates 603 ticks of task-0 sleeps");
    }

    /**
     * The shipped config.toml default is instruction-budget = 1000000 per tick. The demo must
     * complete under it — an early build died mid-gradient-sweep on a real server because
     * BlockPalette converted its whole table to Oklab on every nearest() call, and only an
     * uncapped test budget hid it.
     */
    @Test
    void demoSurvivesTheShippedInstructionBudget() throws IOException {
        Run run = run(1_000_000);

        assertInstanceOf(TickResult.Finished.class, run.result());
        assertEquals(ExitCode.END, ((TickResult.Finished) run.result()).exitCode());
        assertEquals(EXPECTED_FINISH_TICK, run.finishTick());
    }

    @Test
    void everyEntityKindIsSpawned() throws IOException {
        RecordingRenderer r = run().renderer();

        // 46 block displays: 15 panel + 5 stairs + furnace + repeater + log + 16 strip + lamp
        // + runner + 5 logo members.
        assertEquals(46, r.count("spawn"));
        assertEquals(3, r.count("spawnTextDisplay"));
        assertEquals(1, r.count("spawnItemDisplay"));
        assertEquals(1, r.count("spawnArmorStand"));
        assertEquals(1, r.count("spawnItem"));

        // Ids are dense and in spawn order across all five kinds — one id space, one registry.
        List<Integer> spawnIds = r.events.stream().filter(e -> e.kind().startsWith("spawn"))
                .map(Event::id).toList();
        assertEquals(IntStream.rangeClosed(1, TOTAL_ENTITIES).boxed().toList(), spawnIds);

        // Spot-check the kinds land on the ids the table says, with their content.
        assertEquals("minecraft:diamond_sword", spawnOf(r, SWORD_ID).text());
        assertEquals("spawnItemDisplay", spawnOf(r, SWORD_ID).kind());
        assertEquals("spawnArmorStand", spawnOf(r, USHER_ID).kind());
        assertEquals("minecraft:emerald", spawnOf(r, EMERALD_ID).text());
        assertEquals("spawnItem", spawnOf(r, EMERALD_ID).kind());
        // The typed block-state builder and the rare string property both survive the round trip.
        assertEquals("minecraft:oak_stairs[facing=west,half=top]", spawnOf(r, 16).text());
        assertEquals("minecraft:furnace[facing=north,lit=true]", spawnOf(r, 21).text());
        assertEquals("minecraft:repeater[facing=west,delay=3]", spawnOf(r, 22).text());
        assertEquals("minecraft:oak_log[axis=y]", spawnOf(r, 23).text());
    }

    @Test
    void gradientSweepRepaintsEveryStripTile() throws IOException {
        RecordingRenderer r = run().renderer();

        // Section F: 30 frames, every tile re-picking its nearest palette block each frame. The
        // first tile takes one extra set in section N, which flips it to a lit lamp for punctuation.
        for (int id = STRIP_FIRST; id <= STRIP_LAST; id++) {
            final int tile = id;
            long repaints = r.of("setBlock").stream().filter(e -> e.id() == tile).count();
            long expected = id == STRIP_FIRST ? SWEEP_FRAMES + 1 : SWEEP_FRAMES;
            assertEquals(expected, repaints, "tile " + tile + " repaints");
        }
        assertEquals("minecraft:redstone_lamp[lit=true]", r.of("setBlock").stream()
                .filter(e -> e.id() == STRIP_FIRST).toList().getLast().text(),
                "the farewell flips the first tile to a lit lamp");
        // Every repaint names a real palette block, and the tiles do not all agree at once
        // (a gradient sliding under them, not a flat fill).
        long distinctBlocks = r.of("setBlock").stream().map(Event::text).distinct().count();
        assertTrue(distinctBlocks >= 4, "expected several palette colours, got " + distinctBlocks);
        assertTrue(r.of("setBlock").stream().allMatch(e -> e.text().startsWith("minecraft:")));
    }

    @Test
    void logoGroupTransformsEveryMemberTogether() throws IOException {
        RecordingRenderer r = run().renderer();

        // Sections G+H: a group transform recomputes every member's world state, so all five
        // members must receive exactly the same number of position and rotation sets.
        long expectedPositions = countFor(r, "setPosition", LOGO_FIRST);
        long expectedRotations = countFor(r, "setRotation", LOGO_FIRST);
        assertTrue(expectedPositions >= 7,
                "orbit + pulse should move each member at least 7 times, got " + expectedPositions);
        for (int id = LOGO_FIRST; id <= LOGO_LAST; id++) {
            assertEquals(expectedPositions, countFor(r, "setPosition", id), "member " + id);
            assertEquals(expectedRotations, countFor(r, "setRotation", id), "member " + id);
        }
        // Members orbit rather than spin in place. The first member is the group's pivot (the
        // quartz hub), so it is recomputed to the same spot every time; the four arms move.
        assertEquals(1, distinctPositions(r, LOGO_FIRST), "the hub sits on the pivot and stays");
        for (int id = LOGO_FIRST + 1; id <= LOGO_LAST; id++) {
            assertTrue(distinctPositions(r, id) > 1, "arm " + id + " must orbit the hub");
        }
    }

    @Test
    void swordTimelineSubStepsItsEasedKeyframes() throws IOException {
        RecordingRenderer r = run().renderer();

        // Section I: a 60-tick timeline chunked two ticks at a time, so ~30 sub-steps, each
        // setting the full transform; the landing keyframe also changes the display context.
        assertTrue(countFor(r, "setPosition", SWORD_ID) >= 30,
                "expected ~30 timeline sub-steps, got " + countFor(r, "setPosition", SWORD_ID));
        assertEquals(countFor(r, "setPosition", SWORD_ID), countFor(r, "setRotation", SWORD_ID),
                "each sub-step sets position and rotation together");
        assertTrue(countFor(r, "setDisplayContext", SWORD_ID) > 0, "the landing sets Ground context");
    }

    @Test
    void armorStandPosesAndYawAreHostTweened() throws IOException {
        RecordingRenderer r = run().renderer();

        // Section K: three arm waves (up/down) and one slow turn. Armor stands have no client
        // interpolation, so every one of these must arrive with over_ticks > 0 for the host tween.
        List<Event> poses = r.of("setPose");
        assertEquals(6, poses.size(), "three waves = six pose sets");
        assertTrue(poses.stream().allMatch(e -> e.id() == USHER_ID));
        assertTrue(poses.stream().allMatch(e -> e.over() == 8),
                "each wave leg is an 8-tick host tween");
        // Part 3 is the right arm, and the euler degrees alternate up/down.
        assertTrue(poses.stream().allMatch(e -> (int) e.nums()[0] == 3), "right arm is part 3");
        assertEquals(-120.0, poses.get(0).nums()[1], 1e-9);
        assertEquals(-15.0, poses.get(1).nums()[1], 1e-9);

        List<Event> yaw = r.of("setYaw");
        assertEquals(1, yaw.size());
        assertEquals(180.0, yaw.getFirst().nums()[0], 1e-9);
        assertEquals(20L, yaw.getFirst().over(), "the turn is a 20-tick host tween");

        // Equipment reaches three different slots (helmet, main hand, off hand).
        assertEquals(3, r.count("setEquipment"));
        assertEquals(List.of("minecraft:player_head", "minecraft:netherite_sword", "minecraft:torch"),
                r.of("setEquipment").stream().map(Event::text).toList());
    }

    @Test
    void soundsAndEveryParticleVariantAreEmitted() throws IOException {
        RecordingRenderer r = run().renderer();

        assertEquals(List.of("minecraft:block.note_block.pling", "minecraft:block.note_block.chime",
                "minecraft:block.note_block.bass"), r.of("playSound").stream().map(Event::text).toList());
        // Category, volume and pitch all survive the ABI: the pling is volume 1.5, pitch 1.2,
        // category Record (2); the chime is volume 0.8, pitch 1.6, category Block (4).
        Event pling = r.of("playSound").getFirst();
        assertEquals(2.0, pling.nums()[3], 1e-9);
        assertEquals(1.5, pling.nums()[4], 1e-9);
        assertEquals(1.2, pling.nums()[5], 1e-9);
        Event chime = r.of("playSound").get(1);
        assertEquals(4.0, chime.nums()[3], 1e-9);
        assertEquals(0.8, chime.nums()[4], 1e-9);
        assertEquals(1.6, chime.nums()[5], 1e-9);

        List<String> variants = r.of("emitParticle").stream()
                .map(e -> e.text().substring(0, e.text().indexOf('('))).distinct().sorted().toList();
        assertEquals(List.of("block", "dust", "dustTransition", "named"), variants,
                "the demo exercises all five imports (block, item and named share the named form)");
        assertTrue(r.count("emitParticle") > 50, "the dust orbit emits many particles");
    }

    @Test
    void headlineTypesItselfOutThenMarquees() throws IOException {
        RecordingRenderer r = run().renderer();

        // Section E: the headline is revealed one character at a time inside a gradient tag, so
        // successive payloads for that entity grow by exactly one visible character.
        List<String> headline = r.of("setText").stream().filter(e -> e.id() == HEADLINE_ID)
                .map(Event::text).filter(t -> t.contains("<gradient:")).toList();
        assertTrue(headline.size() >= 11, "NOW SHOWING is 11 characters, got " + headline.size());
        List<String> revealed = headline.stream().map(DemoIntegrationTest::insideGradient).toList();
        for (int i = 1; i < revealed.size(); i++) {
            assertEquals(revealed.get(i - 1).length() + 1, revealed.get(i).length(),
                    "the typewriter adds one character per frame: " + revealed);
            assertTrue(revealed.get(i).startsWith(revealed.get(i - 1)), "and only appends");
        }
        assertEquals("NOW SHOWING", revealed.get(10), "the finished headline");

        // The performer's marquee scrolls a 12-character window over
        // "** NOW SHOWING · BILLBOARD v2 **" on its own display, and section N's farewell scrolls a
        // 10-character window of "THANKS FOR WATCHING" (19 characters, so 19 frames).
        List<String> window12 = r.of("setText").stream().map(Event::text)
                .filter(t -> t.length() == 12).toList();
        assertTrue(window12.size() >= 12, "expected the scrolling window frames: " + window12.size());
        assertTrue(window12.contains("** NOW SHOWI"), window12.toString());
        assertTrue(window12.contains("NOW SHOWING "), window12.toString());
        assertEquals(19, r.of("setText").stream().map(Event::text)
                .filter(t -> t.length() == 10).count(), "19 farewell frames");
        // Slicing is by character, not byte: the middle dot arrives whole.
        assertTrue(window12.stream().anyMatch(t -> t.contains("·")), "the · survives slicing");
        // Text attributes reach the host too.
        assertTrue(r.count("setTextBackground") > 0);
        assertTrue(r.count("setTextOpacity") > 0);
        assertTrue(r.count("setLineWidth") > 0);
        assertTrue(r.count("setTextFlags") > 0);
    }

    @Test
    void lampCheckpointRestoreReplaysTheWholeState() throws IOException {
        RecordingRenderer r = run().renderer();

        // Section M restores the checkpoint the lamp's state() took at spawn: an instant
        // (over = 0) burst of every attribute, ending back on its spawn block.
        assertEquals(1, countFor(r, "setPosition", LAMP_ID));
        assertEquals(1, countFor(r, "setRotation", LAMP_ID));
        Event position = r.of("setPosition").stream().filter(e -> e.id() == LAMP_ID)
                .findFirst().orElseThrow();
        assertEquals(0L, position.over(), "a restore is instant, not interpolated");
        assertArrayCloseTo(spawnOf(r, LAMP_ID).nums(), position.nums());
        Event rotation = r.of("setRotation").stream().filter(e -> e.id() == LAMP_ID)
                .findFirst().orElseThrow();
        assertArrayCloseTo(new double[] {0.0, 0.0, 0.0, 1.0}, rotation.nums());
        assertEquals("minecraft:sea_lantern", r.of("setBlock").stream()
                .filter(e -> e.id() == LAMP_ID).findFirst().orElseThrow().text());
    }

    @Test
    void everySpawnedEntityIsDespawnedExactlyOnce() throws IOException {
        Run run = run();
        List<Event> despawns = run.renderer().of("despawn");

        assertEquals(TOTAL_ENTITIES, despawns.size(), "one despawn per entity ever spawned");
        for (int id = 1; id <= TOTAL_ENTITIES; id++) {
            final int target = id;
            assertEquals(1L, despawns.stream().filter(e -> e.id() == target).count(),
                    "id " + id + " despawned exactly once");
        }
    }

    @Test
    void theDemoNeverTouchesTheNonDeterministicStream() throws IOException {
        // random_nondet is in the module's import list because default_random() picks its stream at
        // run time and both arms are linked; the seeded flag is set during init, so it is never
        // called. Asserting on calls, not imports (demo/src/lib.rs:44-48).
        assertEquals(0L, run().nonDeterministicDraws());
    }

    @Test
    void traceIsIdenticalAcrossRuns() throws IOException {
        Run first = run();
        Run second = run();

        // The whole host-call trace, at full precision, including the random-derived values
        // (the third sound's pitch is a deterministic draw). Any leak from the non-deterministic
        // stream would show up right here.
        List<String> a = first.renderer().trace();
        List<String> b = second.renderer().trace();
        assertEquals(a.size(), b.size(), "same number of host calls");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i), "host call #" + i + " differs between runs");
        }
        assertEquals(first.logs(), second.logs());
        assertEquals(first.finishTick(), second.finishTick());
    }

    @Test
    void theNarrationLogsTellTheStoryInOrder() throws IOException {
        List<String> logs = run().logs();

        assertOrderedSubsequence(logs, "demo v2: cue given", "demo v2: spotlight on the usher",
                "demo v2: goodnight");
        // The checkpoint restore reports the scale it read back from the host before restoring.
        assertTrue(logs.stream().anyMatch(l -> l.contains("lamp scale before restore = 1.00")),
                "the checkpoint read-back log is present: " + logs);
        // And the armor stand's getters read back the values the host was told.
        assertTrue(logs.stream().anyMatch(l -> l.contains("usher yaw 180deg")), logs.toString());
    }

    // --- helpers ---

    private static Event spawnOf(RecordingRenderer r, int id) {
        return r.events.stream().filter(e -> e.kind().startsWith("spawn") && e.id() == id)
                .findFirst().orElseThrow(() -> new AssertionError("no spawn for id " + id));
    }

    /** How many distinct positions one entity was moved to. */
    private static long distinctPositions(RecordingRenderer r, int id) {
        return r.of("setPosition").stream().filter(e -> e.id() == id)
                .map(e -> java.util.Arrays.toString(e.nums())).distinct().count();
    }

    private static long countFor(RecordingRenderer r, String kind, int id) {
        return r.of(kind).stream().filter(e -> e.id() == id).count();
    }

    /** The text between {@code <gradient:…>} and {@code </gradient>} — the revealed characters. */
    private static String insideGradient(String miniMessage) {
        int open = miniMessage.indexOf('>', miniMessage.indexOf("<gradient:")) + 1;
        int close = miniMessage.indexOf("</gradient>");
        return miniMessage.substring(open, close);
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
