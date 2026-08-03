package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.billboard.placement.ViewerPosition;
import com.jhuanglululu.billboard.runtime.PlayerView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * World space to placement-local, for player positions and facing.
 *
 * <p>The rotated cases are hand-computed from {@link Rotation}'s stated convention rather than from
 * its matrix code: at yaw 90 the placement faces world −X, so {@code R} maps local
 * {@code (x, y, z)} to world {@code (−z, y, x)} and its transpose maps world {@code (a, b, c)} back
 * to {@code (c, b, −a)}. Every expected number below follows from those two lines. The remaining
 * cases are round trips against the <em>forward</em> transform in {@link Origin}, which is a
 * different code path.
 */
class PlayerFrameTest {

    private static final double EPS = 1e-9;

    private static ViewerPosition viewer(double x, double y, double z, double yaw, double pitch) {
        return new ViewerPosition(UUID.randomUUID(), "p", "world", x, y, z, yaw, pitch, 1.62);
    }

    @Test
    void anUnrotatedPlacementOnlyTranslates() {
        Origin origin = new Origin("world", 10, 64, -20);

        PlayerView v = PlayerFrame.toLocal(origin, viewer(13, 66, -18, 45, 10));

        assertEquals(3, v.x(), EPS);
        assertEquals(2, v.y(), EPS);
        assertEquals(2, v.z(), EPS);
        assertEquals(45, v.yaw(), 1e-9);
        assertEquals(10, v.pitch(), 1e-9);
        assertEquals(1.62, v.eyeHeight());
        assertEquals("p", v.name());
    }

    @Test
    void aYaw90PlacementMapsAKnownWorldPointToItsHandComputedLocal() {
        // Placement at (0, 64, 0) turned to face world -X. Rᵀ·(a, b, c) = (c, b, -a).
        Origin origin = new Origin("world", 0, 64, 0, new Rotation(90, 0, 0));

        // world (5, 65, 3): delta from the origin is (5, 1, 3), so local is (3, 1, -5).
        PlayerView v = PlayerFrame.toLocal(origin, viewer(5, 65, 3, 0, 0));

        assertEquals(3, v.x(), EPS);
        assertEquals(1, v.y(), EPS);
        assertEquals(-5, v.z(), EPS);
    }

    @Test
    void aYaw90PlacementMapsAKnownFacingToItsHandComputedLocal() {
        Origin origin = new Origin("world", 0, 64, 0, new Rotation(90, 0, 0));

        // A player looking at world yaw 0 faces world +Z = (0, 0, 1). Rᵀ·(0,0,1) = (1, 0, 0),
        // i.e. local +X — and local yaw is atan2(-x', z') = atan2(-1, 0) = -90 degrees.
        assertEquals(-90, PlayerFrame.toLocal(origin, viewer(0, 64, 0, 0, 0)).yaw(), 1e-9);
        // Looking at world yaw 90 means facing world -X, which is the placement's own forward: 0.
        assertEquals(0, PlayerFrame.toLocal(origin, viewer(0, 64, 0, 90, 0)).yaw(), 1e-9);
        // Yaw does not disturb pitch: the rotation is about the world Y axis the pitch is measured
        // against, so looking 30 degrees down is still 30 degrees down in the placement's frame.
        assertEquals(30, PlayerFrame.toLocal(origin, viewer(0, 64, 0, 0, 30)).pitch(), 1e-9);
    }

    @Test
    void positionRoundTripsBackThroughTheForwardTransform() {
        // The inverse really is an inverse, on a rotation with all three angles engaged.
        Origin origin = new Origin("world", -7.25, 71.5, 13.75, new Rotation(37, -21, 64));
        double wx = 3.5;
        double wy = 80.25;
        double wz = -19.0;

        PlayerView v = PlayerFrame.toLocal(origin, viewer(wx, wy, wz, 0, 0));

        assertEquals(wx, origin.worldX(v.x(), v.y(), v.z()), 1e-9);
        assertEquals(wy, origin.worldY(v.x(), v.y(), v.z()), 1e-9);
        assertEquals(wz, origin.worldZ(v.x(), v.y(), v.z()), 1e-9);
    }

    @Test
    void facingRoundTripsBackThroughTheForwardTransform() {
        Origin origin = new Origin("world", 0, 0, 0, new Rotation(37, -21, 64));
        double worldYaw = 123.5;
        double worldPitch = -42.0;

        PlayerView v = PlayerFrame.toLocal(origin, viewer(0, 0, 0, worldYaw, worldPitch));

        // Rebuild the look vector from the local angles, rotate it forward, and it must be the
        // world look vector the player actually had.
        double ly = Math.toRadians(v.yaw());
        double lp = Math.toRadians(v.pitch());
        double lfx = -Math.sin(ly) * Math.cos(lp);
        double lfy = -Math.sin(lp);
        double lfz = Math.cos(ly) * Math.cos(lp);
        Rotation r = origin.rotation();

        double wy = Math.toRadians(worldYaw);
        double wp = Math.toRadians(worldPitch);
        assertEquals(-Math.sin(wy) * Math.cos(wp), r.rotatedX(lfx, lfy, lfz), 1e-9);
        assertEquals(-Math.sin(wp), r.rotatedY(lfx, lfy, lfz), 1e-9);
        assertEquals(Math.cos(wy) * Math.cos(wp), r.rotatedZ(lfx, lfy, lfz), 1e-9);
    }

    @Test
    void straightUpIsTheDomainEdgeAndStaysFinite() {
        // Pitch ±90 is where asin's domain edge lives: rounding on a unit vector can push the y
        // component a hair past 1, which would be NaN without the clamp.
        PlayerView v = PlayerFrame.toLocal(new Origin("world", 0, 0, 0), viewer(0, 0, 0, 0, -90));
        assertEquals(-90, v.pitch(), 1e-9);
    }

    @Test
    void aPitchedPlacementReExpressesFacingInItsOwnTiltedFrame() {
        // Pitch 90 tips the placement's forward straight down, so R maps local (x, y, z) to world
        // (x, -z, y) and Rᵀ maps world (a, b, c) to (a, c, -b).
        Origin origin = new Origin("world", 0, 0, 0, new Rotation(0, 90, 0));

        // A player looking straight up faces world (0, 1, 0); Rᵀ·(0, 1, 0) = (0, 0, -1), which is
        // local backwards — yaw 180, pitch 0. (The placement's own forward is world-down, so
        // "up" is behind it, not above it.)
        PlayerView up = PlayerFrame.toLocal(origin, viewer(0, 0, 0, 0, -90));
        assertEquals(0, up.pitch(), 1e-9);
        assertEquals(180, Math.abs(up.yaw()), 1e-9);

        // And looking straight down faces world (0, -1, 0) -> local (0, 0, 1): dead ahead.
        PlayerView down = PlayerFrame.toLocal(origin, viewer(0, 0, 0, 0, 90));
        assertEquals(0, down.pitch(), 1e-9);
        assertEquals(0, down.yaw(), 1e-9);
    }
}
