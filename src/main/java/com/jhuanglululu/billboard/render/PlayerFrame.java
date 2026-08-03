package com.jhuanglululu.billboard.render;

import com.jhuanglululu.billboard.placement.ViewerPosition;
import com.jhuanglululu.billboard.runtime.PlayerView;

/**
 * Converts a world-space {@link ViewerPosition} into the placement-local {@link PlayerView} the
 * guest sees. Pure (no Bukkit, no PacketEvents), so every number below is unit-testable, and it is
 * the exact inverse of the outgoing transform {@link Origin} applies to entity positions.
 *
 * <h2>Position</h2>
 *
 * <p>{@code local = Rᵀ · (world − origin)} — {@link Origin#localX} and friends. Nothing subtle: a
 * rotation matrix's inverse is its transpose.
 *
 * <h2>Facing</h2>
 *
 * <p>Angles cannot be subtracted componentwise once pitch and roll are in play, so the look
 * <em>direction</em> is what actually travels through the frame:
 *
 * <ol>
 *   <li>build the world unit look vector from Minecraft's own convention —
 *       {@code (−sin yaw · cos pitch, −sin pitch, cos yaw · cos pitch)}, which is the vector
 *       {@link Rotation}'s class doc already pins down as {@code F(yaw)} tilted by pitch;</li>
 *   <li>rotate it into the frame with the same {@code Rᵀ};</li>
 *   <li>read the angles back off the result the way Minecraft does:
 *       {@code yaw = atan2(−x′, z′)}, {@code pitch = −asin(y′)}.</li>
 * </ol>
 *
 * <p>Step 3 inverts step 1 exactly, so an identity rotation returns the player's own yaw and pitch
 * (modulo the {@code atan2} range, which normalises yaw into −180..180 — the same angle). Roll has
 * no place in the output: a player's head has only two angles, and the third would have nowhere to
 * go.
 */
public final class PlayerFrame {

    private PlayerFrame() {}

    /** One player, converted out of world space into {@code origin}'s frame. */
    public static PlayerView toLocal(Origin origin, ViewerPosition viewer) {
        double lx = origin.localX(viewer.x(), viewer.y(), viewer.z());
        double ly = origin.localY(viewer.x(), viewer.y(), viewer.z());
        double lz = origin.localZ(viewer.x(), viewer.y(), viewer.z());

        double yawRad = Math.toRadians(viewer.yaw());
        double pitchRad = Math.toRadians(viewer.pitch());
        double cosPitch = Math.cos(pitchRad);
        double fx = -Math.sin(yawRad) * cosPitch;
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * cosPitch;

        Rotation r = origin.rotation();
        double lfx = r.unrotatedX(fx, fy, fz);
        double lfy = r.unrotatedY(fx, fy, fz);
        double lfz = r.unrotatedZ(fx, fy, fz);

        double localYaw = Math.toDegrees(Math.atan2(-lfx, lfz));
        double localPitch = Math.toDegrees(-Math.asin(clamp(lfy)));
        return new PlayerView(viewer.name(), lx, ly, lz, viewer.eyeHeight(), localYaw, localPitch);
    }

    /** {@code asin} refuses anything past ±1, which rounding alone can produce on a unit vector. */
    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
