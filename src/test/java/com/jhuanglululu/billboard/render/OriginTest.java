package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OriginTest {

    @Test
    void addsOriginToRelativeCoordinates() {
        // The reported bug: placement origin (-881, 72, 249), guest emits relative (-2, -1, 0);
        // the world position must be (-883, 71, 249), not the raw relative coords.
        Origin o = new Origin("world", -881, 72, 249);
        assertEquals(-883.0, o.worldX(-2, -1, 0));
        assertEquals(71.0, o.worldY(-2, -1, 0));
        assertEquals(249.0, o.worldZ(-2, -1, 0));
    }

    @Test
    void fractionalAndZeroOrigins() {
        Origin o = new Origin("world", 10.5, 64.0, -20.25);
        assertEquals(12.0, o.worldX(1.5, 0, 0));
        assertEquals(64.0, o.worldY(1.5, 0, 0));
        assertEquals(-20.25, o.worldZ(1.5, 0, 0));

        Origin zero = new Origin("world", 0, 0, 0);
        assertEquals(3.0, zero.worldX(3, 0, 0));
        assertEquals(-4.0, zero.worldZ(0, 0, -4));
    }

    @Test
    void anOriginWithoutARotationIsAPureTranslation() {
        Origin o = new Origin("world", 10, 64, -20);
        assertEquals(Rotation.NONE, o.rotation());
        // Not "close to": the identity path must hand the addition back untouched, which is what
        // makes an unrotated placement byte-for-byte what it was before rotation existed.
        assertEquals(13.5, o.worldX(3.5, 99, -99));
        assertEquals(64.25, o.worldY(-99, 0.25, 99));
        assertEquals(-27.5, o.worldZ(99, -99, -7.5));
    }

    @Test
    void aRotatedOriginTurnsAroundItself() {
        // Origin (100, 64, -50), yaw 90 (the placement faces -X). By RotationTest's hand
        // derivation, yaw 90 sends local (2, 3, 5) to (-5, 3, 2); adding the origin gives
        // (95, 67, -48).
        Origin o = new Origin("world", 100, 64, -50, new Rotation(90, 0, 0));
        assertEquals(95.0, o.worldX(2, 3, 5), 1e-12);
        assertEquals(67.0, o.worldY(2, 3, 5), 1e-12);
        assertEquals(-48.0, o.worldZ(2, 3, 5), 1e-12);

        // The origin itself is the fixed point: local (0,0,0) cannot move, whatever the rotation.
        Origin tilted = new Origin("world", 100, 64, -50, new Rotation(37, -12, 5));
        assertEquals(100.0, tilted.worldX(0, 0, 0), 1e-12);
        assertEquals(64.0, tilted.worldY(0, 0, 0), 1e-12);
        assertEquals(-50.0, tilted.worldZ(0, 0, 0), 1e-12);
    }
}
