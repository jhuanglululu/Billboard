package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OriginTest {

    @Test
    void addsOriginToRelativeCoordinates() {
        // The reported bug: placement origin (-881, 72, 249), guest emits relative (-2, -1, 0);
        // the world position must be (-883, 71, 249), not the raw relative coords.
        Origin o = new Origin("world", -881, 72, 249);
        assertEquals(-883.0, o.worldX(-2));
        assertEquals(71.0, o.worldY(-1));
        assertEquals(249.0, o.worldZ(0));
    }

    @Test
    void fractionalAndZeroOrigins() {
        Origin o = new Origin("world", 10.5, 64.0, -20.25);
        assertEquals(12.0, o.worldX(1.5));
        assertEquals(64.0, o.worldY(0));
        assertEquals(-20.25, o.worldZ(0));

        Origin zero = new Origin("world", 0, 0, 0);
        assertEquals(3.0, zero.worldX(3));
        assertEquals(-4.0, zero.worldZ(-4));
    }
}
