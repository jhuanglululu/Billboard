package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The seed policy is Billboard's, not the engine's: the engine takes an instance seed and asks
 * no questions, while <em>which</em> seed a placement gets is what makes a {@code per_player}
 * billboard show the same player the same variation every visit.
 */
class StableSeedTest {

    @Test
    void stableSeedIsAFixedFunctionOfAnimationPlacementAndOwner() {
        // FNV-1a 64 over "demo\0p1\0Steve".
        assertEquals(0x4478C52C0505CA1AL, AnimationInstance.stableSeed("demo", "p1", "Steve"));
        assertEquals(AnimationInstance.stableSeed("demo", "p1", "Steve"),
                AnimationInstance.stableSeed("demo", "p1", "Steve"));
        assertNotEquals(AnimationInstance.stableSeed("demo", "p1", "Steve"),
                AnimationInstance.stableSeed("demo", "p1", "Alex"));
        // The separators keep the three parts from bleeding into each other.
        assertNotEquals(AnimationInstance.stableSeed("demo", "p1", "x"),
                AnimationInstance.stableSeed("demo", "p1x", ""));
    }
}
