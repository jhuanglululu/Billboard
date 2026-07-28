package com.jhuanglululu.billboard.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProximityControllerTest {

    private static final class FakeLifecycle implements InstanceLifecycle<String> {
        int started;
        int stopped;
        final Set<String> live = new LinkedHashSet<>();

        @Override
        public String start(Placement placement, Set<ViewerPosition> viewers) {
            String handle = "inst" + started;
            started++;
            live.add(handle);
            return handle;
        }

        @Override
        public void setViewers(String handle, Set<ViewerPosition> viewers) {
            // recorded implicitly by keeping the handle live
        }

        @Override
        public void stop(String handle) {
            stopped++;
            live.remove(handle);
        }
    }

    private static final class FakePositions implements PositionSource {
        final List<ViewerPosition> players = new ArrayList<>();

        @Override
        public Collection<ViewerPosition> onlinePlayers() {
            return players;
        }
    }

    private static BillboardConfig config(int linger) {
        return new BillboardConfig(
                BillboardConfig.defaults().runtime(),
                new BillboardConfig.Proximity(64, 20, linger),
                List.of(),
                true);
    }

    private static ViewerPosition viewer(UUID id, String name, double x) {
        return new ViewerPosition(id, name, "world", x, 64, 0);
    }

    @Test
    void sharedApproachLingerReapproachAndDeath() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));

        UUID alice = UUID.randomUUID();

        c.check(0);
        assertEquals(0, life.live.size());          // nobody near

        pos.players.add(viewer(alice, "alice", 5));
        c.check(10);
        assertEquals(1, life.live.size());           // started on approach
        assertEquals(1, life.started);
        assertEquals(1, c.activeInstanceCount());

        c.check(20);
        assertEquals(1, life.started);               // still one instance, not restarted

        pos.players.clear();
        c.check(30);                                 // depart -> linger begins (death at 130)
        assertEquals(1, life.live.size());           // still running while lingering
        assertEquals(0, life.stopped);

        c.check(100);
        assertEquals(1, life.live.size());           // still lingering (< 130)

        pos.players.add(viewer(alice, "alice", 5));
        c.check(120);                                // returns within the window
        assertEquals(1, life.live.size());
        assertEquals(1, life.started);               // kept, not restarted

        pos.players.clear();
        c.check(130);                                // depart again -> death at 230
        assertEquals(1, life.live.size());
        c.check(230);                                // linger elapsed -> stop
        assertEquals(0, life.live.size());
        assertEquals(1, life.stopped);
    }

    @Test
    void pauseStopsSharedInstanceImmediately() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertEquals(1, life.live.size());

        data.animation("demo").setPaused(true);
        c.check(1);
        assertEquals(0, life.live.size());           // paused -> stopped at once (no linger)
        assertEquals(1, life.stopped);
    }

    @Test
    void pausedPlacementInstantiatesNothingWhileItsSiblingKeepsRunning() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        data.putPlacement(new Placement("demo", "spot2", "world", 10, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));   // in range of both
        c.check(0);
        assertEquals(2, life.live.size());

        data.putPlacement(data.placement("demo", "spot1").orElseThrow().withPaused(true));
        c.check(1);
        assertEquals(1, life.live.size());           // the paused one stopped at once, no linger
        assertEquals(1, life.stopped);

        c.check(2);
        c.check(3);
        assertEquals(2, life.started, "a paused placement must never be instantiated again");

        // resumed: it comes back on the next check
        data.putPlacement(data.placement("demo", "spot1").orElseThrow().withPaused(false));
        c.check(4);
        assertEquals(2, life.live.size());
        assertEquals(3, life.started);
    }

    @Test
    void perPlayerPausedPlacementStartsNothing() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.PER_PLAYER, VisibilityMode.EVERYONE).withPaused(true));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        pos.players.add(viewer(UUID.randomUUID(), "bob", -5));
        c.check(0);
        assertEquals(0, life.started);
        assertEquals(0, c.activeInstanceCount());
    }

    @Test
    void perPlayerRunsOneInstancePerEligiblePlayer() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.PER_PLAYER, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(50));

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        pos.players.add(viewer(alice, "alice", 5));
        pos.players.add(viewer(bob, "bob", -5));
        c.check(0);
        assertEquals(2, life.live.size());           // one instance each
        assertEquals(2, c.activeInstanceCount());

        // Bob leaves; his instance lingers then dies, Alice's stays.
        pos.players.removeIf(v -> v.uuid().equals(bob));
        c.check(10);
        assertEquals(2, life.live.size());           // bob lingering (death at 60)
        c.check(60);
        assertEquals(1, life.live.size());           // bob's stopped, alice's remains
    }

    /** Records every offered hint; {@code deliver} models "the player is an admin/log-viewer". */
    private static final class FakeHints implements PauseHintSink {
        final List<String> sent = new ArrayList<>();
        boolean deliver = true;

        @Override
        public boolean hint(Placement placement, ViewerPosition viewer, boolean animationLevel) {
            if (!deliver) {
                return false;
            }
            sent.add(placement.key() + " -> " + viewer.name() + (animationLevel ? " [animation]" : " [placement]"));
            return true;
        }
    }

    @Test
    void pausedPlacementNudgesEachNearbyPlayerExactlyOnce() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE).withPaused(true));
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        ProximityController<String> c = new ProximityController<>(pos, new FakeLifecycle(), data, () -> config(100));
        c.setPauseHintSink(hints);

        UUID alice = UUID.randomUUID();
        pos.players.add(viewer(alice, "alice", 5));
        pos.players.add(viewer(UUID.randomUUID(), "far", 5000));   // out of range: never told

        c.check(0);
        c.check(1);
        c.check(2);
        assertEquals(List.of("demo/spot1 -> alice [placement]"), hints.sent);
    }

    @Test
    void animationPauseNudgesWithTheAnimationLevelFlag() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.NONE));   // not even a viewer: still told
        data.animation("demo").setPaused(true);
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        ProximityController<String> c = new ProximityController<>(pos, new FakeLifecycle(), data, () -> config(100));
        c.setPauseHintSink(hints);

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertEquals(List.of("demo/spot1 -> alice [animation]"), hints.sent);
    }

    @Test
    void resumingResetsTheNudgeSoARePauseTellsAgain() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE).withPaused(true));
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        ProximityController<String> c = new ProximityController<>(pos, new FakeLifecycle(), data, () -> config(100));
        c.setPauseHintSink(hints);

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertEquals(1, hints.sent.size());

        data.putPlacement(data.placement("demo", "spot1").orElseThrow().withPaused(false));
        c.check(1);
        assertEquals(1, hints.sent.size(), "an unpaused placement says nothing");

        data.putPlacement(data.placement("demo", "spot1").orElseThrow().withPaused(true));
        c.check(2);
        c.check(3);
        assertEquals(2, hints.sent.size(), "a fresh pause is worth telling about again");
    }

    @Test
    void clearingHintsMakesTheNudgeRepeat() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE).withPaused(true));
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        ProximityController<String> c = new ProximityController<>(pos, new FakeLifecycle(), data, () -> config(100));
        c.setPauseHintSink(hints);

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        c.check(1);
        assertEquals(1, hints.sent.size());

        c.clearPauseHints();   // /billboard reload
        c.check(2);
        assertEquals(2, hints.sent.size());
    }

    @Test
    void anUndeliveredNudgeIsStillOwedTheNextTime() {
        // The sink refuses players who are neither admin nor log-viewer; if that changes (op'd,
        // added to log-viewers) they must still get the hint, so a refusal records nothing.
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE).withPaused(true));
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        hints.deliver = false;
        ProximityController<String> c = new ProximityController<>(pos, new FakeLifecycle(), data, () -> config(100));
        c.setPauseHintSink(hints);

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertTrue(hints.sent.isEmpty());

        hints.deliver = true;
        c.check(1);
        assertEquals(1, hints.sent.size());
    }

    @Test
    void loadSkippedPlacementIsNotNudgedAboutSinceResumeCannotFixIt() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "spot1", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeHints hints = new FakeHints();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));
        c.setPauseHintSink(hints);
        c.setSkippedPlacements(() -> Set.of("demo/spot1"));

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertEquals(0, life.started, "a load-skipped placement still instantiates nothing");
        assertTrue(hints.sent.isEmpty(), "its failure was already reported loudly at load time");
    }

    @Test
    void removingPlacementStopsItsInstance() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        FakeLifecycle life = new FakeLifecycle();
        ProximityController<String> c = new ProximityController<>(pos, life, data, () -> config(100));

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);
        assertEquals(1, life.live.size());

        data.removePlacement("demo", "sq");
        c.check(1);
        assertEquals(0, life.live.size());
        assertTrue(life.stopped >= 1);
    }

    @Test
    void instanceStartFailurePausesReportsOnceAndDoesNotRetry() {
        DataStore data = new DataStore();
        data.putPlacement(new Placement("demo", "sq", "world", 0, 64, 0,
                InstanceType.SHARED, VisibilityMode.EVERYONE));
        FakePositions pos = new FakePositions();
        InstanceLifecycle<String> throwing = new InstanceLifecycle<>() {
            @Override
            public String start(Placement placement, Set<ViewerPosition> viewers) {
                throw new IllegalStateException("no loaded module for animation demo");
            }

            @Override
            public void setViewers(String handle, Set<ViewerPosition> viewers) { }

            @Override
            public void stop(String handle) { }
        };
        int[] reports = {0};
        ProximityController<String> c = new ProximityController<>(pos, throwing, data, () -> config(100));
        c.setStartFailureHandler((animation, message) -> reports[0]++);

        pos.players.add(viewer(UUID.randomUUID(), "alice", 5));
        c.check(0);   // start attempt throws -> paused + reported
        c.check(1);   // paused -> no start attempt (no retry)
        c.check(2);

        assertTrue(data.animation("demo").paused());
        assertEquals(1, reports[0], "start failure must be reported exactly once, not per check");
        assertEquals(0, c.activeInstanceCount());
    }
}
