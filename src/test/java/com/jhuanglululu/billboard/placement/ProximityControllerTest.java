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
                List.of());
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
