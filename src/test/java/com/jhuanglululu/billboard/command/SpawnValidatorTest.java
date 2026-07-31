package com.jhuanglululu.billboard.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpawnValidatorTest {

    @Test
    void acceptsALoadedAnimation() {
        assertTrue(SpawnValidator.rejectUnknown("demo", Set.of("demo", "clock")).isEmpty());
    }

    @Test
    void rejectsUnknownAndListsLoadedNames() {
        Optional<String> error = SpawnValidator.rejectUnknown("nope", List.of("demo", "clock"));
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("nope"));
        assertTrue(error.get().contains("demo"));
        assertTrue(error.get().contains("clock"));
    }

    @Test
    void rejectsWhenNothingLoaded() {
        Optional<String> error = SpawnValidator.rejectUnknown("x", Set.of());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("(none)"));
    }

    @Test
    void badCoordinateNamesTheAxisAndTheToken() {
        String error = SpawnValidator.badCoordinate("y", "sixty-four");
        assertTrue(error.contains("y coordinate"), error);
        assertTrue(error.contains("<white>sixty-four</white>"), error);
        assertTrue(error.contains("<red>"), error);
    }

    @Test
    void badCoordinateEscapesTheRejectedToken() {
        assertTrue(SpawnValidator.badCoordinate("x", "<b>").contains("\\<b>"));
    }

    @Test
    void relativeWithoutAPlayerSaysWhoCanUseItAndWhatToDoInstead() {
        String error = SpawnValidator.relativeNeedsPlayer();
        assertTrue(error.contains("<red>"), error);
        assertTrue(error.contains("<white>~</white>"), error);
        assertTrue(error.contains("player"), error);
        assertFalse(error.contains(".</red>"), "house style: no trailing periods");
    }

    @Test
    void escapesUntrustedAnimationName() {
        Optional<String> error = SpawnValidator.rejectUnknown("<b>x", Set.of("demo"));
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("\\<b>x"));      // escaped
        assertFalse(error.get().contains("<b>x</b>"));   // no injected tag pair
    }
}
