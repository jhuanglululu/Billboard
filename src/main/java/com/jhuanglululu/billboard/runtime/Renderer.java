package com.jhuanglululu.billboard.runtime;

/**
 * The visual side effects an animation produces, as the runtime observes them. The
 * plugin implements this with client-side entity packets; tests implement it with a
 * recorder. Every id is a host-allocated entity id from the {@link EntityRegistry}.
 *
 * <p><b>Coordinate contract:</b> every position ({@code spawn*}, {@code setPosition},
 * {@code playSound}, {@code emitParticle}) is <em>relative to the placement origin</em> —
 * callers always pass origin-relative coordinates, and it is the implementation's
 * responsibility to translate them to absolute world coordinates (add the origin) before
 * rendering. Rotations, scales and poses carry no origin.
 *
 * <p>{@code overTicks} is the interpolation duration for a change ({@code 0} = instant);
 * the runtime forwards it verbatim — smoothing is the renderer's concern, not the
 * host-side source of truth. For display entities that means a metadata duration the client
 * honours; for armor stands and item entities it means a host tween ({@link EntityTweens}).
 *
 * <p><b>Validation contract:</b> block states, items and MiniMessage text reaching this
 * interface have already been validated by the runtime, so an implementation may assume they
 * parse. Sound ids have <em>not</em> been validated and never are.
 */
public interface Renderer {

    // --- spawning ---

    /** Spawn a block display showing {@code blockState} at origin-relative {@code (x, y, z)}. */
    void spawnBlockDisplay(int id, String blockState, double x, double y, double z);

    /** Spawn an item display showing {@code item} (vanilla component format). */
    void spawnItemDisplay(int id, String item, double x, double y, double z);

    /** Spawn a text display showing the MiniMessage string {@code text}. */
    void spawnTextDisplay(int id, String text, double x, double y, double z);

    /** Spawn an armor stand (no equipment, zero poses, yaw 0). */
    void spawnArmorStand(int id, double x, double y, double z);

    /** Spawn a dropped-item-look entity showing {@code item}; packet-only, so uncollectable. */
    void spawnItem(int id, String item, double x, double y, double z);

    // --- shared transform ---

    /** Move {@code id} to origin-relative {@code (x, y, z)}, interpolating over {@code overTicks}. */
    void setPosition(int id, double x, double y, double z, long overTicks);

    /** Rotate display {@code id} to quaternion {@code (qx, qy, qz, qw)} over {@code overTicks}. */
    void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks);

    /** Scale display {@code id} to {@code (sx, sy, sz)} over {@code overTicks}. */
    void setScale(int id, double sx, double sy, double sz, long overTicks);

    // --- per-kind attributes ---

    /** Swap {@code id}'s displayed block (instant — blocks cannot interpolate). */
    void setBlock(int id, String blockState);

    /** Swap the item of an item display or item entity. */
    void setItem(int id, String item);

    /**
     * Set an item display's display context: {@code 0 none, 1..4 third/first-person hands,
     * 5 head, 6 gui, 7 ground, 8 fixed} — nine values, matching vanilla
     * {@code ItemDisplayTransform}.
     */
    void setDisplayContext(int id, int context);

    /** Set any display's billboard mode ({@code 0 fixed, 1 vertical, 2 horizontal, 3 center}). */
    void setBillboardMode(int id, int mode);

    /** Replace a text display's MiniMessage text. */
    void setText(int id, String text);

    /** Set a text display's background colour (ARGB). */
    void setTextBackground(int id, long argb);

    /** Set a text display's text opacity ({@code 0..255}). */
    void setTextOpacity(int id, long opacity);

    /** Set a text display's line width in pixels. */
    void setLineWidth(int id, long width);

    /** Set a text display's flag bits ({@code bit0 shadow, bit1 see-through, bit2 default background}). */
    void setTextFlags(int id, int flags);

    /** Set one armor-stand pose part ({@code 0 head .. 5 right leg}) in euler degrees. */
    void setPose(int id, int part, double xDeg, double yDeg, double zDeg, long overTicks);

    /** Set an armor-stand equipment slot ({@code 0 helmet .. 3 boots, 4 main hand, 5 off hand}). */
    void setEquipment(int id, int slot, String item);

    /** Set an armor stand's flag bits ({@code bit0 small, bit1 arms, bit2 no baseplate, bit3 invisible}). */
    void setStandFlags(int id, int flags);

    /** Turn an armor stand to {@code yawDegrees} over {@code overTicks}. */
    void setYaw(int id, double yawDegrees, long overTicks);

    /** Remove {@code id} from the world. */
    void despawn(int id);

    // --- fire-and-forget effects ---

    /**
     * Play a sound. {@code name} is <em>never validated</em> — an unknown id is silently nothing,
     * the one documented exception to the error philosophy.
     *
     * @param category the protocol sound category, {@code 0 master .. 9 voice}
     */
    void playSound(String name, double x, double y, double z, int category, double volume,
            double pitch);

    /** Emit particles. */
    void emitParticle(ParticleSpec.Emission emission);

    /**
     * Advance host-side tweens by one tick and send whatever they moved. Called once per tick
     * from the animation's own tick path, after the interpreter has run.
     */
    void tickTweens();
}
