package com.jhuanglululu.billboard.placement;

import java.util.UUID;

/**
 * A snapshot of one online player, taken on the main thread: identity, world position, where they
 * are looking, and how high their eyes currently sit.
 *
 * <p>Proximity only ever needed the position. ABI 4's {@code players()} needs the rest, and it
 * needs all of it captured in the <em>same</em> instant: host functions run on worker threads and
 * may never touch live Bukkit state, so a snapshot is the only thing they can be given, and a
 * snapshot that mixed a position from one tick with a facing from another would be a lie the guest
 * cannot detect.
 *
 * <p>All values are world-space; the placement-local frame conversion happens per instance (see
 * {@code render/PlayerFrame}), because two placements looking at the same player see different
 * numbers.
 *
 * @param uuid      the player's account id
 * @param name      the player's account name — the identity {@code bb.player}, the whitelist and
 *                  the guest's {@code player_update} all key on
 * @param world     the world name
 * @param x         feet x
 * @param y         feet y (the block the player is standing on, not eye level)
 * @param z         feet z
 * @param yaw       Minecraft yaw in degrees (0 = +Z, 90 = -X)
 * @param pitch     Minecraft pitch in degrees (positive = down)
 * @param eyeHeight the player's current eye height above their feet, from
 *                  {@code Player#getEyeHeight()} — it already accounts for sneaking, which is
 *                  exactly why the host reads it rather than letting the guest assume 1.62
 */
public record ViewerPosition(UUID uuid, String name, String world, double x, double y, double z,
        double yaw, double pitch, double eyeHeight) {

    /** A standing player's eye height, for callers with nothing better (tests, proximity fakes). */
    public static final double DEFAULT_EYE_HEIGHT = 1.62;

    /**
     * A position-only snapshot, looking straight ahead from a standing player's eye height — what
     * the proximity logic, which reads none of the new fields, has always needed.
     */
    public ViewerPosition(UUID uuid, String name, String world, double x, double y, double z) {
        this(uuid, name, world, x, y, z, 0, 0, DEFAULT_EYE_HEIGHT);
    }
}
