package com.jhuanglululu.billboard.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.billboard.data.Env;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who a running instance is allowed to be told about. */
class PlayerSnapshotsTest {

    private static ViewerPosition at(String name, double x) {
        return new ViewerPosition(UUID.randomUUID(), name, "world", x, 64, 0);
    }

    // alice is close, bob is close, mallory is close, far is 100 blocks out.
    private static final List<ViewerPosition> ONLINE =
            List.of(at("alice", 1), at("bob", 2), at("mallory", 3), at("far", 100));

    private static Placement placement(Map<String, String> env, VisibilityMode visibility,
            Set<String> whitelist, Set<String> blacklist) {
        return new Placement("demo", "lobby", "world", 0, 64, 0, 0, 0, 0, env, visibility,
                false, whitelist, blacklist);
    }

    private static List<String> names(List<ViewerPosition> viewers) {
        return viewers.stream().map(ViewerPosition::name).toList();
    }

    @Test
    void aSharedInstanceSeesEveryEligibleViewer() {
        List<ViewerPosition> got = PlayerSnapshots.forInstance(
                placement(Map.of(), VisibilityMode.EVERYONE, Set.of(), Set.of()),
                "EVERYONE", ONLINE, Map.of(), 64);

        assertEquals(List.of("alice", "bob", "mallory"), names(got), "far is out of range");
    }

    @Test
    void aPerPlayerInstanceSeesOnlyItsOwner() {
        // Its audience is one person; telling it about the others would leak players into an
        // animation only the owner can see anything of.
        List<ViewerPosition> got = PlayerSnapshots.forInstance(
                placement(Map.of(Env.TYPE, "per_player"), VisibilityMode.EVERYONE,
                        Set.of(), Set.of()),
                "bob", ONLINE, Map.of(), 64);

        assertEquals(List.of("bob"), names(got));
    }

    @Test
    void theVisibilityFilterAppliesToTheSnapshotToo() {
        List<ViewerPosition> got = PlayerSnapshots.forInstance(
                placement(Map.of(), VisibilityMode.BLACKLIST, Set.of(), Set.of("mallory")),
                "EVERYONE", ONLINE, Map.of(), 64);

        assertEquals(List.of("alice", "bob"), names(got));
    }

    @Test
    void groupsExpandInTheSnapshotFilterAsTheyDoForVisibility() {
        List<ViewerPosition> got = PlayerSnapshots.forInstance(
                placement(Map.of(), VisibilityMode.WHITELIST, Set.of("vips"), Set.of()),
                "EVERYONE", ONLINE, Map.of("vips", Set.of("bob", "far")), 64);

        assertEquals(List.of("bob"), names(got), "far passes the filter but is out of range");
    }

    @Test
    void anOwnerWhoWalkedOutOfRangeIsNoLongerInTheSnapshot() {
        // The instance keeps running through its linger window; its player list going empty is
        // exactly how a guest learns nobody is there any more.
        List<ViewerPosition> got = PlayerSnapshots.forInstance(
                placement(Map.of(Env.TYPE, "per_player"), VisibilityMode.EVERYONE,
                        Set.of(), Set.of()),
                "far", ONLINE, Map.of(), 64);

        assertEquals(List.of(), names(got));
    }
}
