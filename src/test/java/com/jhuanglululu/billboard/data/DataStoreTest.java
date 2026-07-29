package com.jhuanglululu.billboard.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {

    @Test
    void roundTripThroughTheDataFolder(@TempDir Path dir) {
        Path data = dir.resolve("data");

        DataStore out = new DataStore();
        out.putPlacement(new Placement("demo", "square", "world", 10.5, 64.0, -20.0,
                InstanceType.SHARED, VisibilityMode.WHITELIST));
        out.putPlacement(new Placement("clock", "lobby", "world_nether", 1, 2, 3,
                InstanceType.PER_PLAYER, VisibilityMode.BLACKLIST));
        AnimationSettings demo = out.animation("demo");
        demo.setPaused(true);
        demo.whitelist().addAll(List.of("alice", "vips"));
        demo.blacklist().add("mallory");
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

        Placement q = in.placement("clock", "lobby").orElseThrow();
        assertEquals(InstanceType.PER_PLAYER, q.type());
        assertEquals(VisibilityMode.BLACKLIST, q.visibility());

        AnimationSettings loadedDemo = in.animation("demo");
        assertTrue(loadedDemo.paused());
        assertEquals(List.of("alice", "vips"), List.copyOf(loadedDemo.whitelist()));
        assertEquals(List.of("mallory"), List.copyOf(loadedDemo.blacklist()));

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
        // names an instance type that does not exist — both must cost only themselves.
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("placements.jsonl"), """
                {"animation":"demo","id":"a","world":"world","x":1.0,"y":2.0,"z":3.0,"type":"shared","visibility":"everyone","paused":false}
                {"animation":"demo","id":"truncated","world":"wor
                {"animation":"demo","id":"b","world":"nether","x":-4.5,"y":70.0,"z":0.0,"type":"per_player","visibility":"none","paused":true}
                {"animation":"demo","id":"c","world":"world","x":0.0,"y":0.0,"z":0.0,"type":"sometimes","visibility":"everyone","paused":false}
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
    void loadingAnAbsentFolderYieldsAnEmptyStore(@TempDir Path dir) {
        DataStore store = DataStore.load(dir.resolve("data"));
        assertTrue(store.placements().isEmpty());
        assertTrue(store.groupIds().isEmpty());
        assertTrue(store.issues().isEmpty(), "a first run is not a failure");
    }
}
