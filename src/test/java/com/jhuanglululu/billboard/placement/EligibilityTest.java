package com.jhuanglululu.billboard.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EligibilityTest {

    private static final Map<String, Set<String>> GROUPS = Map.of("vips", Set.of("alice", "bob"));

    @Test
    void everyoneAndNone() {
        assertTrue(Eligibility.visibleTo(VisibilityMode.EVERYONE, Set.of(), Set.of(), GROUPS, "carol"));
        assertFalse(Eligibility.visibleTo(VisibilityMode.NONE, Set.of(), Set.of(), GROUPS, "carol"));
    }

    @Test
    void whitelistMatchesDirectlyOrViaGroup() {
        Set<String> wl = Set.of("carol", "vips");
        assertTrue(Eligibility.visibleTo(VisibilityMode.WHITELIST, wl, Set.of(), GROUPS, "carol"));  // direct
        assertTrue(Eligibility.visibleTo(VisibilityMode.WHITELIST, wl, Set.of(), GROUPS, "alice"));  // via group
        assertFalse(Eligibility.visibleTo(VisibilityMode.WHITELIST, wl, Set.of(), GROUPS, "dave"));
    }

    @Test
    void blacklistExcludesDirectlyOrViaGroup() {
        Set<String> bl = Set.of("dave", "vips");
        assertFalse(Eligibility.visibleTo(VisibilityMode.BLACKLIST, Set.of(), bl, GROUPS, "dave"));   // direct
        assertFalse(Eligibility.visibleTo(VisibilityMode.BLACKLIST, Set.of(), bl, GROUPS, "bob"));    // via group
        assertTrue(Eligibility.visibleTo(VisibilityMode.BLACKLIST, Set.of(), bl, GROUPS, "carol"));
    }

    @Test
    void rangeChecksWorldAndDistance() {
        Placement p = new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE);
        assertTrue(Eligibility.inRange(p, viewer("world", 10, 64, 0), 64));
        assertFalse(Eligibility.inRange(p, viewer("world", 100, 64, 0), 64)); // too far
        assertFalse(Eligibility.inRange(p, viewer("nether", 0, 64, 0), 64));  // wrong world
        assertTrue(Eligibility.inRange(p, viewer("world", 64, 64, 0), 64));   // exactly on the radius
    }

    private static ViewerPosition viewer(String world, double x, double y, double z) {
        return new ViewerPosition(UUID.randomUUID(), "p", world, x, y, z);
    }
}
