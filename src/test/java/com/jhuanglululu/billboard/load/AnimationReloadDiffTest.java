package com.jhuanglululu.billboard.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnimationReloadDiffTest {

    @Test
    void detectsAddedChangedAndRemoved() {
        Map<String, Integer> before = Map.of("a", 1, "b", 2, "c", 3);
        Map<String, Integer> after = Map.of("a", 1, "b", 99, "d", 4); // a same, b changed, c gone, d new
        AnimationReloadDiff diff = AnimationReloadDiff.compute(before, after);
        assertEquals(Set.of("d"), diff.added());
        assertEquals(Set.of("b"), diff.changed());
        assertEquals(Set.of("c"), diff.removed());
        assertEquals(Set.of("b", "c"), diff.stopped()); // changed + removed must have instances stopped
    }

    @Test
    void identicalScansYieldNoChanges() {
        Map<String, Integer> m = Map.of("a", 1, "b", 2);
        AnimationReloadDiff diff = AnimationReloadDiff.compute(m, m);
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.changed().isEmpty());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.stopped().isEmpty());
    }

    @Test
    void firstLoadIsAllAdded() {
        AnimationReloadDiff diff = AnimationReloadDiff.compute(Map.of(), Map.of("a", 1, "b", 2));
        assertEquals(Set.of("a", "b"), diff.added());
        assertTrue(diff.changed().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }
}
