package com.jhuanglululu.billboard.render;

/**
 * A placement's rigid rotation, in degrees, of the whole guest coordinate frame about the
 * placement origin: {@code world = origin + R · local}. Pure (no Bukkit, no PacketEvents), so
 * every number below is unit-testable.
 *
 * <h2>The convention, exactly</h2>
 *
 * <p><b>Yaw</b> is Minecraft's yaw, so what a player reads off F3 or types into {@code /tp}
 * transfers unchanged: yaw 0 faces <b>+Z</b>, yaw 90 faces <b>-X</b>, i.e. the facing vector is
 * {@code F(yaw) = (-sin yaw, 0, cos yaw)}. The placement's own frame is defined to face +Z at yaw
 * 0, so {@code R_yaw} is the rotation carrying local +Z onto {@code F(yaw)} — which, in the
 * ordinary right-handed sense, is a rotation about <b>+Y by minus yaw</b>. (Minecraft's yaw runs
 * clockwise seen from above; the right-handed convention runs counter-clockwise. That sign flip
 * <em>is</em> the "Minecraft sign convention", and it lives here and nowhere else.)
 *
 * <p><b>Pitch</b> is Minecraft's pitch: positive pitch looks <b>down</b>. It turns about the
 * <em>yawed</em> X axis (the placement's own left-right axis after yaw), and it is a right-handed
 * rotation about that axis by {@code +pitch}. Check: at yaw 0 the yawed X axis is world +X, and a
 * right-handed rotation about +X by {@code p} sends local +Z to {@code (0, -sin p, cos p)} — which
 * is exactly Minecraft's look vector for pitch {@code p}. Positive pitch tips the placement's
 * forward direction downward, as it should.
 *
 * <p><b>Roll</b> has no vanilla counterpart ({@code /tp} takes no roll), so it is defined as the
 * plain right-handed rotation about the resulting view axis — local +Z after yaw and pitch — by
 * {@code +roll}. Concretely it carries local +X toward local +Y: since +X is the placement's left
 * and +Y is up, <b>positive roll lifts the placement's left side</b>.
 *
 * <p><b>Order</b> is yaw, then pitch, then roll: {@code R = R_yaw · R_pitch · R_roll}. Because
 * pitch and roll are each expressed about the axis the previous rotations already moved, that
 * product is also the intuitive reading — set the yaw, then tilt, then bank — with no extra
 * conjugation terms.
 *
 * <p><b>Identity is free.</b> {@link #NONE} (and any all-zero rotation) reports
 * {@link #isIdentity()} and every operation returns its input untouched, so a placement with no
 * rotation keeps the pure-translation path it has always had.
 */
public final class Rotation {

    /** No rotation at all — the pure-translation path. */
    public static final Rotation NONE = new Rotation(0, 0, 0);

    private final double yaw;
    private final double pitch;
    private final double roll;
    private final boolean identity;

    // R = R_yaw · R_pitch · R_roll, row-major. Precomputed once: a placement's rotation never
    // changes while it is loaded, and this is read on every outgoing position.
    private final double m00;
    private final double m01;
    private final double m02;
    private final double m10;
    private final double m11;
    private final double m12;
    private final double m20;
    private final double m21;
    private final double m22;

    // The same rotation as a unit quaternion (x, y, z, w), for composing with guest-set display
    // rotations. q = q_yaw ⊗ q_pitch ⊗ q_roll, matching the matrix product above.
    private final float qx;
    private final float qy;
    private final float qz;
    private final float qw;

    /**
     * @param yawDegrees   Minecraft yaw (0 = +Z, 90 = -X)
     * @param pitchDegrees Minecraft pitch (positive = down)
     * @param rollDegrees  right-handed roll about the view axis (positive lifts the left side)
     */
    public Rotation(double yawDegrees, double pitchDegrees, double rollDegrees) {
        this.yaw = yawDegrees;
        this.pitch = pitchDegrees;
        this.roll = rollDegrees;
        this.identity = yawDegrees == 0 && pitchDegrees == 0 && rollDegrees == 0;

        double y = Math.toRadians(yawDegrees);
        double p = Math.toRadians(pitchDegrees);
        double r = Math.toRadians(rollDegrees);
        double c = Math.cos(y);
        double s = Math.sin(y);
        double cp = Math.cos(p);
        double sp = Math.sin(p);
        double cr = Math.cos(r);
        double sr = Math.sin(r);

        // R_yaw   = [[ c, 0, -s], [0,  1,   0], [ s, 0,  c]]   (about +Y by -yaw)
        // R_pitch = [[ 1, 0,  0], [0, cp, -sp], [ 0, sp, cp]]  (about +X by +pitch)
        // R_roll  = [[cr,-sr, 0], [sr, cr,  0], [ 0, 0,   1]]  (about +Z by +roll)
        this.m00 = c * cr - s * sp * sr;
        this.m01 = -c * sr - s * sp * cr;
        this.m02 = -s * cp;
        this.m10 = cp * sr;
        this.m11 = cp * cr;
        this.m12 = -sp;
        this.m20 = s * cr + c * sp * sr;
        this.m21 = -s * sr + c * sp * cr;
        this.m22 = c * cp;

        // q_yaw = (0, -sin(y/2), 0, cos(y/2)) — the axis is +Y and the angle is -yaw, so the
        // vector part carries the sign. q_pitch and q_roll are the plain half-angle forms.
        float[] q = multiply(
                multiply(0f, (float) -Math.sin(y / 2), 0f, (float) Math.cos(y / 2),
                        (float) Math.sin(p / 2), 0f, 0f, (float) Math.cos(p / 2)),
                new float[] {0f, 0f, (float) Math.sin(r / 2), (float) Math.cos(r / 2)});
        this.qx = q[0];
        this.qy = q[1];
        this.qz = q[2];
        this.qw = q[3];
    }

    /** Minecraft yaw in degrees. */
    public double yaw() {
        return yaw;
    }

    /** Minecraft pitch in degrees. */
    public double pitch() {
        return pitch;
    }

    /** Roll about the view axis in degrees. */
    public double roll() {
        return roll;
    }

    /** Whether this rotates nothing, so callers can keep their pure-translation path. */
    public boolean isIdentity() {
        return identity;
    }

    /** The x component of {@code R · (x, y, z)}. */
    public double rotatedX(double x, double y, double z) {
        return identity ? x : m00 * x + m01 * y + m02 * z;
    }

    /** The y component of {@code R · (x, y, z)}. */
    public double rotatedY(double x, double y, double z) {
        return identity ? y : m10 * x + m11 * y + m12 * z;
    }

    /** The z component of {@code R · (x, y, z)}. */
    public double rotatedZ(double x, double y, double z) {
        return identity ? z : m20 * x + m21 * y + m22 * z;
    }

    /**
     * The x component of {@code Rᵀ · (x, y, z)} — the inverse rotation.
     *
     * <p>{@code R} is a rotation matrix, so it is orthogonal and its inverse <em>is</em> its
     * transpose: no separate matrix, no numerical drift, and the round trip
     * {@code unrotated(rotated(v)) == v} holds to floating-point accuracy. This is the direction
     * everything <em>incoming</em> travels — a world position or a world look direction being
     * expressed in the guest's own frame — while {@link #rotatedX} carries outgoing entity
     * positions the other way.
     */
    public double unrotatedX(double x, double y, double z) {
        return identity ? x : m00 * x + m10 * y + m20 * z;
    }

    /** The y component of {@code Rᵀ · (x, y, z)}. */
    public double unrotatedY(double x, double y, double z) {
        return identity ? y : m01 * x + m11 * y + m21 * z;
    }

    /** The z component of {@code Rᵀ · (x, y, z)}. */
    public double unrotatedZ(double x, double y, double z) {
        return identity ? z : m02 * x + m12 * y + m22 * z;
    }

    /**
     * Composes a guest-set display rotation with this one: {@code R_placement · q_guest}, as the
     * quaternion {@code (x, y, z, w)} the display's {@code left_rotation} slot carries.
     *
     * <p>The order is not arbitrary. Minecraft's display transform applies {@code left_rotation}
     * to the model <em>after</em> everything the guest expressed, in the entity's own (world-axis
     * aligned) space, and quaternion multiplication applies its right factor first — so rotating
     * the guest's result by the placement rotation is exactly {@code q_placement ⊗ q_guest}, in
     * that order. The wire is Hamilton-convention {@code (x, y, z, w)}, the same order
     * {@code EntityDataTypes.QUATERNION} writes.
     *
     * @return a fresh {@code {x, y, z, w}}; under {@link #isIdentity()} the guest's own values
     */
    public float[] compose(float guestX, float guestY, float guestZ, float guestW) {
        if (identity) {
            return new float[] {guestX, guestY, guestZ, guestW};
        }
        return multiply(qx, qy, qz, qw, guestX, guestY, guestZ, guestW);
    }

    /**
     * Composes a guest-set entity yaw with this placement's yaw. Minecraft yaw is an angle about
     * the same axis in the same direction, so the composition is plain addition — the check is in
     * {@code RotationTest}: turning a facing vector by {@code R_yaw} lands on {@code F(g + yaw)}.
     *
     * <p>Pitch and roll deliberately do not appear: see the armor-stand limitation documented on
     * {@link PacketEventsRenderer}.
     */
    public float composeYaw(float guestYaw) {
        return identity ? guestYaw : (float) (guestYaw + yaw);
    }

    /** Hamilton product {@code a ⊗ b} of two {@code (x, y, z, w)} quaternions. */
    private static float[] multiply(float ax, float ay, float az, float aw,
            float bx, float by, float bz, float bw) {
        return new float[] {
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        };
    }

    private static float[] multiply(float[] a, float[] b) {
        return multiply(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]);
    }
}
