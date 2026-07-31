package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Every expected number here is derived by hand from the convention documented on
 * {@link Rotation} — never by running the implementation and copying what it printed. The
 * derivations are in the comments so a future reader can re-check them without trusting the code.
 *
 * <p>The frame, once, for all of it: the placement's local axes at zero rotation are the world
 * axes. Local <b>+Z is forward</b> (Minecraft yaw 0 faces +Z), local <b>+Y is up</b>, and local
 * <b>+X is the placement's left</b> — facing +Z with +Y up, the right-hand side is -X.
 */
class RotationTest {

    /** Quarter turns are exact in theory and within a few ULPs of sin/cos in practice. */
    private static final double EPS = 1e-12;

    private static void assertVector(double x, double y, double z, Rotation r,
            double lx, double ly, double lz) {
        assertEquals(x, r.rotatedX(lx, ly, lz), EPS, "x");
        assertEquals(y, r.rotatedY(lx, ly, lz), EPS, "y");
        assertEquals(z, r.rotatedZ(lx, ly, lz), EPS, "z");
    }

    @Test
    void zeroRotatesNothingAndSaysSo() {
        assertTrue(Rotation.NONE.isIdentity());
        assertTrue(new Rotation(0, 0, 0).isIdentity());
        assertFalse(new Rotation(90, 0, 0).isIdentity());
        // Identity must be exact, not merely close: it is the path an unrotated placement takes.
        assertEquals(3.25, Rotation.NONE.rotatedX(3.25, -7.5, 0.125));
        assertEquals(-7.5, Rotation.NONE.rotatedY(3.25, -7.5, 0.125));
        assertEquals(0.125, Rotation.NONE.rotatedZ(3.25, -7.5, 0.125));
    }

    @Test
    void yaw90TurnsLeftOntoPlusZ() {
        // Minecraft yaw 90 faces -X. The placement's forward (local +Z) must therefore land on
        // -X, and its left (local +X) on whatever is left of -X: facing -X with +Y up, the right
        // hand points to -Z, so left is +Z.
        Rotation r = new Rotation(90, 0, 0);
        assertVector(0, 0, 1, r, 1, 0, 0);    // local +X (left)    -> +Z
        assertVector(-1, 0, 0, r, 0, 0, 1);   // local +Z (forward) -> -X
        assertVector(0, 1, 0, r, 0, 1, 0);    // local +Y (up)      -> unchanged; yaw is about Y
        // and it is linear, so a mixed offset is just the sum of those three columns
        assertVector(-5, 3, 2, r, 2, 3, 5);
    }

    @Test
    void yaw180AndYawMinus90() {
        // yaw 180 faces -Z: forward flips, and so does left (+X -> -X).
        Rotation half = new Rotation(180, 0, 0);
        assertVector(-1, 0, 0, half, 1, 0, 0);
        assertVector(0, 0, -1, half, 0, 0, 1);

        // yaw -90 faces +X. Facing +X with +Y up, the right hand points to +Z, so left is -Z:
        // local +X -> -Z, and forward local +Z -> +X. It is yaw 90 run backwards.
        Rotation back = new Rotation(-90, 0, 0);
        assertVector(0, 0, -1, back, 1, 0, 0);
        assertVector(1, 0, 0, back, 0, 0, 1);
    }

    @Test
    void pitch90TipsForwardStraightDown() {
        // Minecraft pitch 90 looks straight down, so forward (local +Z) must land on -Y. The
        // rotation is right-handed about the yawed X axis, which at yaw 0 is world +X: about +X,
        // +Y goes to +Z and +Z goes to -Y, while +X itself is the axis and does not move.
        Rotation r = new Rotation(0, 90, 0);
        assertVector(0, -1, 0, r, 0, 0, 1);   // forward -> down
        assertVector(0, 0, 1, r, 0, 1, 0);    // up      -> +Z
        assertVector(1, 0, 0, r, 1, 0, 0);    // left    -> unchanged (it is the axis)
    }

    @Test
    void roll90LiftsTheLeftSide() {
        // Roll is right-handed about the view axis, which at zero yaw and pitch is world +Z:
        // about +Z, +X goes to +Y and +Y goes to -X. Local +X is the placement's left, so
        // positive roll lifts the left side — which is what the class doc promises.
        Rotation r = new Rotation(0, 0, 90);
        assertVector(0, 1, 0, r, 1, 0, 0);
        assertVector(-1, 0, 0, r, 0, 1, 0);
        assertVector(0, 0, 1, r, 0, 0, 1);    // the view axis itself does not move
    }

    @Test
    void yawThenPitchAppliesPitchAboutTheYawedAxis() {
        // R = R_yaw · R_pitch, so a local vector meets pitch first and yaw second.
        //   local +Z --pitch 90--> (0,-1,0) --yaw 90--> (0,-1,0)   (yaw leaves Y alone)
        //   local +Y --pitch 90--> (0, 0,1) --yaw 90--> (-1,0,0)
        //   local +X --pitch 90--> (1, 0,0) --yaw 90--> (0,0,1)
        // The pitch really did happen about the placement's own left-right axis: the forward
        // direction went down, not sideways, even though the placement was turned first.
        Rotation r = new Rotation(90, 90, 0);
        assertVector(0, -1, 0, r, 0, 0, 1);
        assertVector(-1, 0, 0, r, 0, 1, 0);
        assertVector(0, 0, 1, r, 1, 0, 0);
    }

    @Test
    void yaw90AsAQuaternionIsAQuarterTurnAboutMinusY() {
        // Minecraft yaw runs clockwise from above, so R_yaw is a right-handed rotation about +Y
        // by -yaw: axis (0,-1,0), angle 90. Half-angle 45 gives cos = sin = sqrt(2)/2, so
        // q = (x, y, z, w) = (0, -sqrt(2)/2, 0, sqrt(2)/2). Composing with the guest's identity
        // rotation must leave exactly that.
        float half = (float) (Math.sqrt(2) / 2);
        float[] q = new Rotation(90, 0, 0).compose(0f, 0f, 0f, 1f);
        assertEquals(0f, q[0], 1e-6f);
        assertEquals(-half, q[1], 1e-6f);
        assertEquals(0f, q[2], 1e-6f);
        assertEquals(half, q[3], 1e-6f);
    }

    @Test
    void composingAGuestQuaternionMultipliesPlacementFirst() {
        // q_placement = (0, -h, 0, h)  (yaw 90, above)
        // q_guest     = (h,  0, 0, h)  (a quarter turn about +X, whatever the guest meant by it)
        // with h = sqrt(2)/2, so h*h = 1/2. Hamilton product a (x) b, a = placement, b = guest:
        //   w = aw*bw - ax*bx - ay*by - az*bz = h*h - 0 - 0 - 0 =  0.5
        //   x = aw*bx + ax*bw + ay*bz - az*by = h*h + 0 + 0 - 0 =  0.5
        //   y = aw*by - ax*bz + ay*bw + az*bx = 0 - 0 + (-h)*h + 0 = -0.5
        //   z = aw*bz + ax*by - ay*bx + az*bw = 0 + 0 - (-h)*h + 0 =  0.5
        // The placement on the LEFT is the load-bearing part: the wire's left_rotation is applied
        // to the guest's already-rotated model, and a quaternion product applies its right factor
        // first, so this is "guest first, then placement" — a placement rotating the guest's work.
        float half = (float) (Math.sqrt(2) / 2);
        float[] q = new Rotation(90, 0, 0).compose(half, 0f, 0f, half);
        assertEquals(0.5f, q[0], 1e-6f);
        assertEquals(-0.5f, q[1], 1e-6f);
        assertEquals(0.5f, q[2], 1e-6f);
        assertEquals(0.5f, q[3], 1e-6f);
    }

    @Test
    void identityCompositionHandsBackTheGuestsOwnNumbers() {
        float[] q = Rotation.NONE.compose(0.25f, -0.5f, 0.75f, 0.125f);
        assertEquals(0.25f, q[0]);
        assertEquals(-0.5f, q[1]);
        assertEquals(0.75f, q[2]);
        assertEquals(0.125f, q[3]);
    }

    @Test
    void yawComposesByAddition() {
        assertEquals(120f, new Rotation(90, 0, 0).composeYaw(30f), 1e-4f);
        assertEquals(30f, Rotation.NONE.composeYaw(30f));

        // Why addition is the right answer, checked independently of composeYaw: an entity at
        // guest yaw 30 faces F(30) = (-sin 30, 0, cos 30) = (-0.5, 0, 0.8660254). Turning that
        // vector by the placement's own rotation must land on F(120) = (-sin 120, 0, cos 120)
        // = (-0.8660254, 0, -0.5) — the facing of yaw 30 + 90.
        Rotation r = new Rotation(90, 0, 0);
        double sin30 = 0.5;
        double cos30 = 0.8660254037844387;
        assertVector(-cos30, 0, -sin30, r, -sin30, 0, cos30);
    }
}
