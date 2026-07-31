package com.jhuanglululu.billboard.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoordinateTest {

    @Test
    void parsesAnAbsoluteNumber() {
        Coordinate c = Coordinate.parse("10.5");
        assertFalse(c.relative());
        assertEquals(10.5, c.value());
        // An absolute coordinate ignores the base entirely — that is what makes it usable from
        // the console, which has no base to offer.
        assertEquals(10.5, c.resolve(-881));
        assertEquals(10.5, c.resolve(0));
    }

    @Test
    void parsesNegativeAndDottedForms() {
        assertEquals(-881.0, Coordinate.parse("-881").value());
        assertEquals(72.0, Coordinate.parse("+72").value());
        assertEquals(0.5, Coordinate.parse(".5").value());
        assertEquals(3.0, Coordinate.parse("3.").value());
    }

    @Test
    void bareTildeIsThePlayersOwnCoordinate() {
        Coordinate c = Coordinate.parse("~");
        assertTrue(c.relative());
        assertEquals(0.0, c.value());
        assertEquals(-881.25, c.resolve(-881.25));
    }

    @Test
    void tildeWithAnOffsetAddsToThePlayersCoordinate() {
        assertEquals(-878.0, Coordinate.parse("~3").resolve(-881));
        assertEquals(-881.5, Coordinate.parse("~-0.5").resolve(-881));
        assertEquals(74.5, Coordinate.parse("~2.5").resolve(72));

        Coordinate offset = Coordinate.parse("~-0.5");
        assertTrue(offset.relative());
        assertEquals(-0.5, offset.value());
    }

    @Test
    void rejectsThingsThatAreNotCoordinates() {
        // parseDouble would happily take every one of these; a coordinate must not.
        for (String token : new String[] {"", "abc", "1d", "NaN", "Infinity", "0x1p3", "1e3",
                "~~", "~abc", "1 2", "--1", "1.2.3"}) {
            assertThrows(IllegalArgumentException.class, () -> Coordinate.parse(token), token);
        }
    }

    @Test
    void nullIsRejectedRatherThanCrashing() {
        assertThrows(IllegalArgumentException.class, () -> Coordinate.parse(null));
    }
}
