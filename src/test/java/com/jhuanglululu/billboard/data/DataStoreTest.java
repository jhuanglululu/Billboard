package com.jhuanglululu.billboard.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {

    @Test
    void roundTripThroughTheDataFolder(@TempDir Path dir) {
        Path data = dir.resolve("data");

        DataStore out = new DataStore();
        out.putPlacement(new Placement("demo", "square", "world", 10.5, 64.0, -20.0,
                InstanceType.SHARED, VisibilityMode.WHITELIST, false,
                new LinkedHashSet<>(List.of("alice", "vips")), Set.of("mallory")));
        out.putPlacement(new Placement("clock", "lobby", "world_nether", 1, 2, 3,
                InstanceType.PER_PLAYER, VisibilityMode.BLACKLIST));
        out.animation("demo").setPaused(true);
        out.group("vips").addAll(List.of("alice", "bob"));
        out.save(data);

        DataStore in = DataStore.load(data);
        assertTrue(in.issues().isEmpty(), "a file we just wrote must read back cleanly");

        Placement p = in.placement("demo", "square").orElseThrow();
        assertEquals("world", p.world());
        assertEquals(10.5, p.x());
        assertEquals(64.0, p.y());
        assertEquals(-20.0, p.z());
        assertEquals(InstanceType.SHARED, p.type());
        assertEquals(VisibilityMode.WHITELIST, p.visibility());
        // The visibility lists ride on the placement, in the order they were added.
        assertEquals(List.of("alice", "vips"), List.copyOf(p.whitelist()));
        assertEquals(List.of("mallory"), List.copyOf(p.blacklist()));

        Placement q = in.placement("clock", "lobby").orElseThrow();
        assertEquals(InstanceType.PER_PLAYER, q.type());
        assertEquals(VisibilityMode.BLACKLIST, q.visibility());
        assertTrue(q.whitelist().isEmpty(), "a placement given no lists loads with empty ones");
        assertTrue(q.blacklist().isEmpty());

        assertTrue(in.animation("demo").paused());

        assertEquals(List.of("alice", "bob"), List.copyOf(in.group("vips")));
    }

    @Test
    void logMutedRoundTrips(@TempDir Path dir) {
        Path data = dir.resolve("data");
        DataStore out = new DataStore();
        out.logMuted().add("alice");
        out.logMuted().add("bob");
        out.save(data);

        DataStore in = DataStore.load(data);
        assertEquals(List.of("alice", "bob"), List.copyOf(in.logMuted()));

        in.logMuted().remove("alice");
        in.save(data);
        assertEquals(List.of("bob"), List.copyOf(DataStore.load(data).logMuted()));
    }

    @Test
    void removingAPlacementPersists(@TempDir Path dir) {
        Path data = dir.resolve("data");
        DataStore store = new DataStore();
        store.putPlacement(new Placement("demo", "a", "world", 0, 0, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        store.putPlacement(new Placement("demo", "b", "world", 0, 0, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        store.removePlacement("demo", "a");
        store.save(data);

        DataStore reloaded = DataStore.load(data);
        assertFalse(reloaded.placement("demo", "a").isPresent());
        assertTrue(reloaded.placement("demo", "b").isPresent());
    }

    @Test
    void placementPauseFlagRoundTripsIndependentlyOfSiblingsAndOfTheAnimationFlag(@TempDir Path dir) {
        Path data = dir.resolve("data");
        DataStore out = new DataStore();
        out.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        out.putPlacement(new Placement("demo", "spot2", "world", 9, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        out.putPlacement(out.placement("demo", "spot1").orElseThrow().withPaused(true));
        out.save(data);

        DataStore in = DataStore.load(data);
        assertTrue(in.placement("demo", "spot1").orElseThrow().paused());
        assertFalse(in.placement("demo", "spot2").orElseThrow().paused());
        assertFalse(in.animation("demo").paused(), "pausing one placement must not pause the animation");

        // and it survives being cleared again
        in.putPlacement(in.placement("demo", "spot1").orElseThrow().withPaused(false));
        in.save(data);
        assertFalse(DataStore.load(data).placement("demo", "spot1").orElseThrow().paused());
    }

    @Test
    void oneBadLineIsSkippedAndReportedWhileTheRestOfTheFileLoads(@TempDir Path dir) throws IOException {
        // The whole point of JSONL: corruption is per record. Line 2 is truncated JSON and line 4
        // names a visibility mode that does not exist — both must cost only themselves.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"), """
                {"animation":"demo","id":"a","world":"world","x":1.0,"y":2.0,"z":3.0,"type":"shared","visibility":"everyone","paused":false}
                {"animation":"demo","id":"truncated","world":"wor
                {"animation":"demo","id":"b","world":"nether","x":-4.5,"y":70.0,"z":0.0,"env":{"bb.type":"per_player"},"visibility":"none","paused":true}
                {"animation":"demo","id":"c","world":"world","x":0.0,"y":0.0,"z":0.0,"visibility":"sometimes","paused":false}
                """);

        DataStore store = DataStore.load(data);

        assertEquals(List.of("demo/a", "demo/b"), store.placements().stream().map(Placement::key).toList());
        Placement b = store.placement("demo", "b").orElseThrow();
        assertEquals("nether", b.world());
        assertEquals(-4.5, b.x());
        assertEquals(InstanceType.PER_PLAYER, b.type());
        assertTrue(b.paused());

        assertEquals(2, store.issues().size(), () -> "issues: " + store.issues());
        assertTrue(store.issues().get(0).contains("placements.jsonl line 2"), store.issues().get(0));
        assertTrue(store.issues().get(1).contains("placements.jsonl line 4"), store.issues().get(1));
    }

    @Test
    void animationLevelVisibilityListsFromOlderFilesAreIgnoredNotAnError(@TempDir Path dir)
            throws IOException {
        // The lists used to live on the animation. There is no migration: the old keys must load
        // silently, keep the paused flag, and be gone from the file after the next save.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("animations.jsonl"),
                "{\"name\":\"demo\",\"paused\":true,\"whitelist\":[\"alice\"],\"blacklist\":[\"mallory\"]}\n");
        Files.writeString(data.resolve("placements.jsonl"),
                "{\"animation\":\"demo\",\"id\":\"a\",\"world\":\"world\",\"x\":0.0,\"y\":0.0,\"z\":0.0,"
                        + "\"type\":\"shared\",\"visibility\":\"whitelist\",\"paused\":false}\n");

        DataStore store = DataStore.load(data);

        assertTrue(store.issues().isEmpty(), () -> "issues: " + store.issues());
        assertTrue(store.animation("demo").paused());
        Placement p = store.placement("demo", "a").orElseThrow();
        assertTrue(p.whitelist().isEmpty(), "the old animation-level entries must not be adopted");
        assertTrue(p.blacklist().isEmpty());

        store.save(data);
        String rewritten = Files.readString(data.resolve("animations.jsonl"));
        assertFalse(rewritten.contains("whitelist"), rewritten);
    }

    @Test
    void rotationRoundTrips(@TempDir Path dir) {
        Path data = dir.resolve("data");
        DataStore out = new DataStore();
        out.putPlacement(new Placement("demo", "turned", "world", 10.5, 64.0, -20.0,
                90.0, -22.5, 180.0, Map.of(), VisibilityMode.EVERYONE));
        out.putPlacement(new Placement("demo", "straight", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        out.save(data);

        DataStore in = DataStore.load(data);
        assertTrue(in.issues().isEmpty(), () -> "issues: " + in.issues());

        Placement turned = in.placement("demo", "turned").orElseThrow();
        assertEquals(90.0, turned.yaw());
        assertEquals(-22.5, turned.pitch());
        assertEquals(180.0, turned.roll());
        // The convenience constructor's placements stay unrotated through the file, too.
        Placement straight = in.placement("demo", "straight").orElseThrow();
        assertEquals(0.0, straight.yaw());
        assertEquals(0.0, straight.pitch());
        assertEquals(0.0, straight.roll());
    }

    @Test
    void placementsWrittenBeforeRotationExistedLoadAsUnrotated(@TempDir Path dir) throws IOException {
        // The legacy line is exactly what the plugin used to write — no yaw/pitch/roll keys at
        // all. It must load silently (not as a broken record), come back unrotated, and be
        // rewritten with the three keys on the next save, like the animation-level lists before it.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"),
                "{\"animation\":\"demo\",\"id\":\"old\",\"world\":\"world\",\"x\":1.0,\"y\":2.0,"
                        + "\"z\":3.0,\"type\":\"shared\",\"visibility\":\"everyone\","
                        + "\"paused\":false,\"whitelist\":[],\"blacklist\":[]}\n");

        DataStore store = DataStore.load(data);

        assertTrue(store.issues().isEmpty(), () -> "issues: " + store.issues());
        Placement p = store.placement("demo", "old").orElseThrow();
        assertEquals(1.0, p.x());
        assertEquals(0.0, p.yaw());
        assertEquals(0.0, p.pitch());
        assertEquals(0.0, p.roll());

        store.save(data);
        String rewritten = Files.readString(data.resolve("placements.jsonl"));
        assertTrue(rewritten.contains("\"yaw\":0.0"), rewritten);
        assertTrue(rewritten.contains("\"pitch\":0.0"), rewritten);
        assertTrue(rewritten.contains("\"roll\":0.0"), rewritten);
    }

    @Test
    void aRotationKeyThatIsNotANumberIsStillABrokenRecord(@TempDir Path dir) throws IOException {
        // Absent means "old file"; present-but-nonsense means a typo someone must be told about,
        // never a silent zero.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"),
                "{\"animation\":\"demo\",\"id\":\"bad\",\"world\":\"world\",\"x\":0.0,\"y\":0.0,"
                        + "\"z\":0.0,\"yaw\":\"north\",\"type\":\"shared\","
                        + "\"visibility\":\"everyone\",\"paused\":false}\n");

        DataStore store = DataStore.load(data);

        assertTrue(store.placements().isEmpty());
        assertEquals(1, store.issues().size(), () -> "issues: " + store.issues());
        assertTrue(store.issues().get(0).contains("placements.jsonl line 1"), store.issues().get(0));
    }

    @Test
    void aStoredTypeFieldFromAnOlderFileIsIgnoredAndDropped(@TempDir Path dir) throws IOException {
        // The instance type used to be a placement field; it is an env key now, and there is no
        // migration (one server runs this format). A stale "type" must load silently — as shared,
        // whatever it said — and be gone from the file after the next save.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"), """
                {"animation":"demo","id":"a","world":"world","x":1.0,"y":2.0,"z":3.0,"type":"per_player","visibility":"everyone","paused":false}
                """);

        DataStore store = DataStore.load(data);

        assertTrue(store.issues().isEmpty(), () -> "issues: " + store.issues());
        Placement p = store.placement("demo", "a").orElseThrow();
        assertEquals(Map.of(), p.env(), "the stale field must not become an env key");
        assertEquals(InstanceType.SHARED, p.type());

        store.save(data);
        String rewritten = Files.readString(data.resolve("placements.jsonl"));
        assertFalse(rewritten.contains("per_player"), rewritten);
        assertTrue(rewritten.contains("\"env\":{}"), rewritten);
    }

    @Test
    void bothEnvLayersRoundTripAndArePersistedWithSortedKeys(@TempDir Path dir) throws IOException {
        Path data = dir.resolve("data");
        DataStore out = new DataStore();
        // Deliberately inserted out of order, so "sorted on write" is actually being tested.
        Map<String, String> placementEnv = new LinkedHashMap<>();
        placementEnv.put("zoom", "2");
        placementEnv.put(Env.TYPE, "per_player");
        placementEnv.put("caption", "hello there");
        out.putPlacement(new Placement("demo", "lobby", "world", 0, 64, 0,
                0, 0, 0, placementEnv, VisibilityMode.EVERYONE));
        out.animation("demo").env().put("theme", "winter");
        out.animation("demo").env().put("beat", "120");
        out.save(data);

        String placements = Files.readString(data.resolve("placements.jsonl"));
        assertTrue(placements.contains(
                "\"env\":{\"bb.type\":\"per_player\",\"caption\":\"hello there\",\"zoom\":\"2\"}"),
                placements);
        String animations = Files.readString(data.resolve("animations.jsonl"));
        assertTrue(animations.contains("\"env\":{\"beat\":\"120\",\"theme\":\"winter\"}"), animations);

        DataStore in = DataStore.load(data);
        assertTrue(in.issues().isEmpty(), () -> "issues: " + in.issues());
        assertEquals(Map.of("zoom", "2", Env.TYPE, "per_player", "caption", "hello there"),
                in.placement("demo", "lobby").orElseThrow().env());
        assertEquals(Map.of("theme", "winter", "beat", "120"), in.animation("demo").env());
        assertEquals(InstanceType.PER_PLAYER, in.placement("demo", "lobby").orElseThrow().type());
    }

    @Test
    void anEnvValueThatIsNotAStringTakesOnlyItsOwnLineDown(@TempDir Path dir) throws IOException {
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"), """
                {"animation":"demo","id":"a","world":"world","x":0.0,"y":0.0,"z":0.0,"env":{"beat":120},"visibility":"everyone","paused":false}
                {"animation":"demo","id":"b","world":"world","x":0.0,"y":0.0,"z":0.0,"env":{"beat":"120"},"visibility":"everyone","paused":false}
                """);

        DataStore store = DataStore.load(data);

        assertEquals(List.of("demo/b"), store.placements().stream().map(Placement::key).toList());
        assertEquals(1, store.issues().size(), () -> "issues: " + store.issues());
        assertTrue(store.issues().getFirst().contains("placements.jsonl line 1"),
                store.issues().getFirst());
    }

    @Test
    void loadingAnAbsentFolderYieldsAnEmptyStore(@TempDir Path dir) {
        DataStore store = DataStore.load(dir.resolve("data"));
        assertTrue(store.placements().isEmpty());
        assertTrue(store.groupIds().isEmpty());
        assertTrue(store.issues().isEmpty(), "a first run is not a failure");
    }
}
