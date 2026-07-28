package com.jhuanglululu.billboard.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PauseTargetTest {

    private static Placement placement(String animation, String id) {
        return new Placement(animation, id, "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE);
    }

    @Test
    void resolvesAKnownAnimation() {
        PauseTarget t = PauseTarget.resolve("demo", Set.of("demo", "clock"),
                List.of(placement("demo", "spot1")));
        assertEquals(PauseTarget.Kind.ANIMATION, t.kind());
        assertEquals("demo", t.animation());
    }

    @Test
    void resolvesAUniquePlacementId() {
        PauseTarget t = PauseTarget.resolve("spot1", Set.of("demo", "clock"),
                List.of(placement("demo", "spot1"), placement("clock", "lobby")));
        assertEquals(PauseTarget.Kind.PLACEMENT, t.kind());
        assertEquals("demo", t.animation());
        assertEquals("spot1", t.id());
    }

    @Test
    void animationNameWinsOverAPlacementIdSpelledTheSame() {
        // "demo" is both an animation and (under clock) a placement id: the animation reading is
        // the unambiguous one, so pausing "demo" must pause the animation, not clock/demo.
        PauseTarget t = PauseTarget.resolve("demo", Set.of("demo"),
                List.of(placement("clock", "demo")));
        assertEquals(PauseTarget.Kind.ANIMATION, t.kind());
        assertEquals("demo", t.animation());
    }

    @Test
    void ambiguousPlacementIdListsEveryCandidate() {
        PauseTarget t = PauseTarget.resolve("spot1", Set.of("demo", "clock"),
                List.of(placement("demo", "spot1"), placement("clock", "spot1"),
                        placement("clock", "other")));
        assertEquals(PauseTarget.Kind.AMBIGUOUS, t.kind());
        assertEquals("spot1", t.id());
        assertEquals(List.of("demo/spot1", "clock/spot1"), t.candidates());
    }

    @Test
    void unknownWordResolvesToNothing() {
        PauseTarget t = PauseTarget.resolve("nope", Set.of("demo"),
                List.of(placement("demo", "spot1")));
        assertEquals(PauseTarget.Kind.UNKNOWN, t.kind());
        assertTrue(t.candidates().isEmpty());
    }

    @Test
    void anAnimationWithNoLoadedModuleStaysResumableThroughItsSettingsEntry() {
        // The caller passes loaded names ∪ persisted settings names; a broken .wasm keeps its
        // paused flag in data.toml, and that entry is the only way back to a working state.
        PauseTarget t = PauseTarget.resolve("broken", Set.of("broken"), List.of());
        assertEquals(PauseTarget.Kind.ANIMATION, t.kind());
    }
}
