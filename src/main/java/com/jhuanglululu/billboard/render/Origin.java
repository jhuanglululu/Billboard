package com.jhuanglululu.billboard.render;

/**
 * A placement's frame in a world: where the animation's coordinate system sits, and how it is
 * turned. Animation coordinates are origin-relative (see
 * {@link com.jhuanglululu.billboard.runtime.Renderer}); the renderer maps every outgoing position
 * through {@code world = origin + R · local} to get absolute world coordinates. Pure and
 * unit-testable.
 *
 * <p>An unrotated origin ({@link Rotation#NONE}, which is what the four-argument constructor
 * builds) is a pure translation, exactly as before rotation existed: {@link Rotation#isIdentity()}
 * short-circuits the matrix and the coordinates come out {@code x + relativeX} and nothing else.
 */
public final class Origin {

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final Rotation rotation;

    /**
     * An unrotated origin — a pure translation.
     *
     * @param world the world name (informational; viewers are already confined to it by range)
     * @param x     origin x
     * @param y     origin y
     * @param z     origin z
     */
    public Origin(String world, double x, double y, double z) {
        this(world, x, y, z, Rotation.NONE);
    }

    /**
     * @param world    the world name
     * @param x        origin x
     * @param y        origin y
     * @param z        origin z
     * @param rotation how the guest's frame is turned about that origin
     */
    public Origin(String world, double x, double y, double z, Rotation rotation) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rotation = rotation;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** The placement's rotation; {@link Rotation#NONE} when it was never given one. */
    public Rotation rotation() {
        return rotation;
    }

    /** World x of the origin-relative point {@code (relativeX, relativeY, relativeZ)}. */
    public double worldX(double relativeX, double relativeY, double relativeZ) {
        return x + rotation.rotatedX(relativeX, relativeY, relativeZ);
    }

    /** World y of the origin-relative point {@code (relativeX, relativeY, relativeZ)}. */
    public double worldY(double relativeX, double relativeY, double relativeZ) {
        return y + rotation.rotatedY(relativeX, relativeY, relativeZ);
    }

    /** World z of the origin-relative point {@code (relativeX, relativeY, relativeZ)}. */
    public double worldZ(double relativeX, double relativeY, double relativeZ) {
        return z + rotation.rotatedZ(relativeX, relativeY, relativeZ);
    }
}
