package com.jhuanglululu.billboard.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {

    @Test
    void roundTripThroughToml(@TempDir Path dir) {
        Path file = dir.resolve("data.toml");

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
        out.save(file);

        DataStore in = DataStore.load(file);

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
        Path file = dir.resolve("data.toml");
        DataStore out = new DataStore();
        out.logMuted().add("alice");
        out.logMuted().add("bob");
        out.save(file);

        DataStore in = DataStore.load(file);
        assertEquals(List.of("alice", "bob"), List.copyOf(in.logMuted()));

        in.logMuted().remove("alice");
        in.save(file);
        assertEquals(List.of("bob"), List.copyOf(DataStore.load(file).logMuted()));
    }

    @Test
    void removingAPlacementPersists(@TempDir Path dir) {
        Path file = dir.resolve("data.toml");
        DataStore store = new DataStore();
        store.putPlacement(new Placement("demo", "a", "world", 0, 0, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        store.putPlacement(new Placement("demo", "b", "world", 0, 0, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        store.removePlacement("demo", "a");
        store.save(file);

        DataStore reloaded = DataStore.load(file);
        assertFalse(reloaded.placement("demo", "a").isPresent());
        assertTrue(reloaded.placement("demo", "b").isPresent());
    }

    @Test
    void placementPauseFlagRoundTripsIndependentlyOfSiblingsAndOfTheAnimationFlag(@TempDir Path dir) {
        Path file = dir.resolve("data.toml");
        DataStore out = new DataStore();
        out.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        out.putPlacement(new Placement("demo", "spot2", "world", 9, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        out.putPlacement(out.placement("demo", "spot1").orElseThrow().withPaused(true));
        out.save(file);

        DataStore in = DataStore.load(file);
        assertTrue(in.placement("demo", "spot1").orElseThrow().paused());
        assertFalse(in.placement("demo", "spot2").orElseThrow().paused());
        assertFalse(in.animation("demo").paused(), "pausing one placement must not pause the animation");

        // and it survives being cleared again
        in.putPlacement(in.placement("demo", "spot1").orElseThrow().withPaused(false));
        in.save(file);
        assertFalse(DataStore.load(file).placement("demo", "spot1").orElseThrow().paused());
    }

    @Test
    void placementWrittenBeforePerPlacementPauseLoadsUnpaused(@TempDir Path dir) throws Exception {
        // data.toml files predate the flag; a missing key must read as "not paused", not blow up.
        Path file = dir.resolve("data.toml");
        java.nio.file.Files.writeString(file, """
                [[placements]]
                animation = "demo"
                id = "spot1"
                world = "world"
                x = 0.0
                y = 64.0
                z = 0.0
                type = "shared"
                visibility = "everyone"
                """);

        Placement p = DataStore.load(file).placement("demo", "spot1").orElseThrow();
        assertFalse(p.paused());
    }

    @Test
    void loadingAbsentFileYieldsEmptyStore(@TempDir Path dir) {
        DataStore store = DataStore.load(dir.resolve("missing.toml"));
        assertTrue(store.placements().isEmpty());
        assertTrue(store.groupIds().isEmpty());
    }
}
