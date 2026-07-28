package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasmachine.runtime.GuestAbort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the host-side entity registry. */
class EntityRegistryTest {

    @Test
    void spawnInitializesIdentityRotationAndUnitScale() {
        EntityRegistry r = new EntityRegistry();
        int id = r.spawnBlockDisplay("minecraft:stone", 1.0, 2.0, 3.0);
        assertEquals(1, id);
        assertTrue(r.isAlive(id));
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, r.getPosition(id));
        assertArrayEquals(new double[] {0.0, 0.0, 0.0, 1.0}, r.getRotation(id));
        assertArrayEquals(new double[] {1.0, 1.0, 1.0}, r.getScale(id));
        assertEquals("minecraft:stone", r.getBlock(id));
    }

    @Test
    void settersUpdateHostTruth() {
        EntityRegistry r = new EntityRegistry();
        int id = r.spawnBlockDisplay("minecraft:stone", 0, 0, 0);
        r.setPosition(id, 4, 5, 6);
        r.setRotation(id, 0.1, 0.2, 0.3, 0.4);
        r.setScale(id, 2, 3, 4);
        r.setBlock(id, "minecraft:glass");
        assertArrayEquals(new double[] {4, 5, 6}, r.getPosition(id));
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4}, r.getRotation(id));
        assertArrayEquals(new double[] {2, 3, 4}, r.getScale(id));
        assertEquals("minecraft:glass", r.getBlock(id));
    }

    @Test
    void despawnIsIdempotentAndReportsFirstOnly() {
        EntityRegistry r = new EntityRegistry();
        int id = r.spawnBlockDisplay("minecraft:stone", 0, 0, 0);
        assertTrue(r.despawn(id));  // first: was alive
        assertFalse(r.isAlive(id));
        assertFalse(r.despawn(id)); // second: already dead
    }

    @Test
    void accessOnDeadOrUnknownEntityAborts() {
        EntityRegistry r = new EntityRegistry();
        int id = r.spawnBlockDisplay("minecraft:stone", 0, 0, 0);
        r.despawn(id);
        assertThrows(GuestAbort.class, () -> r.setPosition(id, 1, 1, 1));
        assertThrows(GuestAbort.class, () -> r.getScale(id));
        assertThrows(GuestAbort.class, () -> r.getPosition(999));
        assertFalse(r.isAlive(999)); // unknown id is simply not alive (no abort)
    }

    @Test
    void tracksAllSpawnedAndLiveIds() {
        EntityRegistry r = new EntityRegistry();
        int a = r.spawnBlockDisplay("minecraft:a", 0, 0, 0);
        int b = r.spawnBlockDisplay("minecraft:b", 0, 0, 0);
        int c = r.spawnBlockDisplay("minecraft:c", 0, 0, 0);
        r.despawn(b);
        assertEquals(List.of(a, b, c), r.allSpawned());
        assertEquals(List.of(a, c), r.liveIds());
    }
}
