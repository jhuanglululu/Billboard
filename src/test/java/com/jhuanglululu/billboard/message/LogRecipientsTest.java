package com.jhuanglululu.billboard.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LogRecipientsTest {

    @Test
    void keepsOnlyConfiguredViewersWhoAreOnlineAndNotMuted() {
        List<String> viewers = List.of("alice", "bob", "carol");
        Set<String> muted = Set.of("bob");
        Set<String> online = Set.of("alice", "bob", "dave"); // carol offline, dave not a viewer
        // alice: viewer, online, not muted -> in. bob: muted -> out. carol: offline -> out.
        assertEquals(Set.of("alice"), LogRecipients.effective(viewers, muted, online));
    }

    @Test
    void preservesConfigOrder() {
        List<String> viewers = List.of("carol", "alice", "bob");
        Set<String> online = Set.of("alice", "bob", "carol");
        assertEquals(List.of("carol", "alice", "bob"),
                List.copyOf(LogRecipients.effective(viewers, Set.of(), online)));
    }

    @Test
    void emptyWhenNoViewerIsOnline() {
        assertTrue(LogRecipients.effective(List.of("alice"), Set.of(), Set.of("bob")).isEmpty());
    }

    @Test
    void aNonViewerCannotBecomeARecipientEvenIfOnline() {
        // Only config viewers are ever recipients; an online non-viewer is never included.
        assertTrue(LogRecipients.effective(List.of(), Set.of(), Set.of("mallory")).isEmpty());
    }
}
