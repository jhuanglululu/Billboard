package com.jhuanglululu.billboard.runtime;

/**
 * A particle emission, as the runtime hands it to the renderer: one of the five typed shapes
 * the ABI exposes, plus the placement and spread every shape shares. Colours are 0..1 floats
 * (the guest's {@code Color} crosses as f64), positions are origin-relative like every other
 * renderer coordinate.
 *
 * <p>Fire-and-forget: particles have no ids, no handles and no state on either side.
 */
public sealed interface ParticleSpec {

    /** The escape hatch: any particle id, no extra data. Unknown ids kill (unlike sounds). */
    record Named(String name) implements ParticleSpec {}

    /** {@code minecraft:dust} — a coloured dust cloud of the given size. */
    record Dust(double red, double green, double blue, double size) implements ParticleSpec {}

    /** {@code minecraft:dust_color_transition} — dust fading between two colours. */
    record DustTransition(double fromRed, double fromGreen, double fromBlue,
            double toRed, double toGreen, double toBlue, double size) implements ParticleSpec {}

    /** {@code minecraft:block} — block-break particles of a block state. */
    record Block(String blockState) implements ParticleSpec {}

    /** {@code minecraft:item} — item-break particles of an item stack. */
    record Item(String item) implements ParticleSpec {}

    /**
     * Where and how a {@link ParticleSpec} is emitted.
     *
     * @param particle the shape and its data
     * @param x        origin-relative x
     * @param y        origin-relative y
     * @param z        origin-relative z
     * @param count    how many particles
     * @param offsetX  gaussian spread on x
     * @param offsetY  gaussian spread on y
     * @param offsetZ  gaussian spread on z
     * @param speed    the protocol's max-speed field
     */
    record Emission(ParticleSpec particle, double x, double y, double z, int count,
            double offsetX, double offsetY, double offsetZ, double speed) {}
}
