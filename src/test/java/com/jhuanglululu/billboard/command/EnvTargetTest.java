package com.jhuanglululu.billboard.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which of the two env layers a target word names. */
class EnvTargetTest {

    private static Placement placement(String animation, String id) {
        return new Placement(animation, id, "world", 0, 64, 0, 0, 0, 0, Map.of(),
                VisibilityMode.EVERYONE);
    }

    private static final List<Placement> PLACEMENTS = List.of(
            placement("demo", "lobby"), placement("demo", "roof"), placement("clock", "lobby"));

    private static EnvTarget resolve(String token) {
        return EnvTarget.resolve(token, Set.of("demo", "clock", "unplaced"), PLACEMENTS);
    }

    @Test
    void aBareWordIsAnAnimation() {
        assertEquals(new EnvTarget(EnvTarget.Kind.ANIMATION, "demo", null), resolve("demo"));
        // Including one that has no placements at all: the animation layer still exists, and
        // setting it before placing anything is a perfectly reasonable order to work in.
        assertEquals(new EnvTarget(EnvTarget.Kind.ANIMATION, "unplaced", null), resolve("unplaced"));
    }

    @Test
    void aSlashedWordIsOnePlacement() {
        assertEquals(new EnvTarget(EnvTarget.Kind.PLACEMENT, "demo", "roof"), resolve("demo/roof"));
        // "lobby" belongs to two animations — the very ambiguity a bare placement id would have.
        // The qualified form has none: each names exactly one thing.
        assertEquals(new EnvTarget(EnvTarget.Kind.PLACEMENT, "demo", "lobby"), resolve("demo/lobby"));
        assertEquals(new EnvTarget(EnvTarget.Kind.PLACEMENT, "clock", "lobby"), resolve("clock/lobby"));
    }

    @Test
    void aBarePlacementIdIsNotAnAnimation() {
        // Deliberately unlike pause/resume: "lobby" is an id, not an animation, so it resolves to
        // nothing rather than to an ambiguous guess.
        assertEquals(EnvTarget.Kind.UNKNOWN, resolve("lobby").kind());
    }

    @Test
    void unknownNamesResolveToNothing() {
        assertEquals(EnvTarget.Kind.UNKNOWN, resolve("nope").kind());
        assertEquals(EnvTarget.Kind.UNKNOWN, resolve("demo/nope").kind());
        assertEquals(EnvTarget.Kind.UNKNOWN, resolve("nope/lobby").kind());
        assertEquals(EnvTarget.Kind.UNKNOWN, resolve("unplaced/lobby").kind(),
                "a known animation with no such placement is still not a placement");
    }

    @Test
    void theLabelIsTheWordThatWasTyped() {
        assertEquals("demo", resolve("demo").label());
        assertEquals("demo/roof", resolve("demo/roof").label());
    }
}
