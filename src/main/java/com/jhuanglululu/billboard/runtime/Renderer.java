package com.jhuanglululu.billboard.runtime;

/**
 * The visual side effects an animation produces, as the runtime observes them. The
 * plugin implements this with client-side entity packets; tests implement it with a
 * recorder. All coordinates are relative to the animation origin, and every id is a
 * host-allocated entity id from the {@link EntityRegistry}.
 *
 * <p>{@code overTicks} is the interpolation duration for a change ({@code 0} = instant);
 * the runtime forwards it verbatim — smoothing is the renderer's concern, not the
 * host-side source of truth.
 */
public interface Renderer {

    /** Spawn a block display showing {@code blockState} at {@code (x, y, z)}. */
    void spawnBlockDisplay(int id, String blockState, double x, double y, double z);

    /** Move {@code id} to {@code (x, y, z)}, interpolating over {@code overTicks}. */
    void setPosition(int id, double x, double y, double z, long overTicks);

    /** Rotate {@code id} to quaternion {@code (qx, qy, qz, qw)} over {@code overTicks}. */
    void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks);

    /** Scale {@code id} to {@code (sx, sy, sz)} over {@code overTicks}. */
    void setScale(int id, double sx, double sy, double sz, long overTicks);

    /** Swap {@code id}'s displayed block (instant — blocks cannot interpolate). */
    void setBlock(int id, String blockState);

    /** Remove {@code id} from the world. */
    void despawn(int id);
}
