package com.jhuanglululu.billboard.render;

/**
 * A placement's origin in a world. Animation coordinates are origin-relative (see
 * {@link com.jhuanglululu.billboard.runtime.Renderer}); the renderer adds the origin to
 * every outgoing position to get absolute world coordinates. Pure and unit-testable.
 *
 * @param world the world name (informational; viewers are already confined to it by range)
 * @param x     origin x
 * @param y     origin y
 * @param z     origin z
 */
public record Origin(String world, double x, double y, double z) {

    public double worldX(double relativeX) {
        return x + relativeX;
    }

    public double worldY(double relativeY) {
        return y + relativeY;
    }

    public double worldZ(double relativeZ) {
        return z + relativeZ;
    }
}
