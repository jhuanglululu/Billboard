package com.jhuanglululu.billboard.runtime;

/**
 * One player as an animation sees them: a frozen snapshot, already expressed in the placement's own
 * coordinate frame, ready to be packed onto the wire by {@link PlayerBlob}.
 *
 * <p>Everything here has been converted from world space by {@code render/PlayerFrame} — position
 * through the inverse origin/rotation, facing by rotating the world look direction into the frame
 * and re-deriving the angles from it. A guest that placed a billboard at yaw 90 and asks where a
 * player is gets an answer in the same axes it spawns entities in.
 *
 * @param name      the player's account name
 * @param x         feet x, placement-local
 * @param y         feet y, placement-local
 * @param z         feet z, placement-local
 * @param eyeHeight the player's current eye height above their feet (a scalar, so no frame
 *                  applies); already accounts for sneaking
 * @param yaw       yaw in degrees in the placement's frame, vanilla convention (0 = local +Z)
 * @param pitch     pitch in degrees in the placement's frame, vanilla convention (positive = down)
 */
public record PlayerView(String name, double x, double y, double z, double eyeHeight,
        double yaw, double pitch) {

    /** The six {@code f64}s the wire carries for one player, in ABI order. */
    public double[] values() {
        return new double[] {x, y, z, eyeHeight, yaw, pitch};
    }
}
