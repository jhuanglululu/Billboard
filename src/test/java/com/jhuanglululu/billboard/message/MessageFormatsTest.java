package com.jhuanglululu.billboard.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MessageFormatsTest {

    @Test
    void escapeInsertsBackslashBeforeEveryTagStartAndBackslash() {
        // MiniMessage treats '\<' as a literal '<', so escaping every '<' (and '\')
        // neutralizes tag injection. Exact-output assertions, hand-computed.
        assertEquals("\\<red>", MessageFormats.escape("<red>"));
        assertEquals("\\<click:run_command:'/op'>", MessageFormats.escape("<click:run_command:'/op'>"));
        assertEquals("hi\\\\there", MessageFormats.escape("hi\\there")); // lone backslash doubled
        assertEquals("plain text", MessageFormats.escape("plain text"));  // nothing to escape
    }

    @Test
    void guestLogComposesTheDocumentedShape() {
        String line = MessageFormats.guestLog("demo", MessageFormats.EVERYONE, "assembling panel");
        assertTrue(line.contains("Billboard"));
        assertTrue(line.contains("demo"));
        assertTrue(line.contains("owned by"));
        assertTrue(line.contains("EVERYONE"));
        assertTrue(line.contains("logged"));
        assertTrue(line.contains("assembling panel"));
    }

    @Test
    void guestLogEscapesUntrustedAnimationAndMessage() {
        String line = MessageFormats.guestLog("<b>evil", "alice", "<click:run_command:'/op'>x");
        // Untrusted parts arrive already escaped (each '<' becomes '\<').
        assertTrue(line.contains("\\<b>evil"));
        assertTrue(line.contains("\\<click:run_command:'/op'>x"));
    }
}
