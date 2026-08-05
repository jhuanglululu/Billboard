package com.jhuanglululu.billboard.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A user-placed instance of an animation, addressed by {@code (animation, id)}. Persisted
 * in placements.jsonl; survives restarts.
 *
 * <p>The visibility filter lives here, not on the animation: two placements of the same animation
 * are two different signs, and who may read one says nothing about the other.
 *
 * @param animation  the animation name (a {@code .wasm} file stem)
 * @param id         the user-chosen placement id
 * @param world      the world name
 * @param x          origin x
 * @param y          origin y
 * @param z          origin z
 * @param yaw        Minecraft yaw in degrees of the whole placement (0 = +Z, 90 = -X); the guest's
 *                   coordinate frame is rotated rigidly about the origin, see
 *                   {@link com.jhuanglululu.billboard.render.Rotation}
 * @param pitch      Minecraft pitch in degrees (positive = down), about the yawed X axis
 * @param roll       roll in degrees about the resulting view axis
 * @param env        this placement's own environ layer — free-form strings handed to the guest,
 *                   overriding the animation-level layer and overridden by the host built-ins (see
 *                   {@link Env}). The reserved {@link Env#TYPE} key replaced the spawn grammar's
 *                   {@code <type>} argument as the source of truth for {@link #type()};
 *                   insertion-ordered and unmodifiable
 * @param visibility who may see this placement
 * @param paused     whether {@code /billboard pause <id>} disabled this one placement; a paused
 *                   placement instantiates nothing, exactly like the animation-level flag but
 *                   without touching the animation's other placements
 * @param whitelist  the entries (player names and/or group ids) {@code visibility = whitelist}
 *                   consults; insertion-ordered and unmodifiable
 * @param blacklist  the entries {@code visibility = blacklist} consults
 */
public record Placement(String animation, String id, String world, double x, double y, double z,
        double yaw, double pitch, double roll, Map<String, String> env, VisibilityMode visibility,
        boolean paused, Set<String> whitelist, Set<String> blacklist) {

    public Placement {
        env = Env.frozen(env);
        whitelist = frozen(whitelist);
        blacklist = frozen(blacklist);
    }

    /**
     * A new, unpaused, unrotated placement with empty lists and an instance type — the shape
     * callers that only care about the type want. {@code spawn} is not one of them: it names no
     * type at all and passes an empty env.
     */
    public Placement(String animation, String id, String world, double x, double y, double z,
            InstanceType type, VisibilityMode visibility) {
        this(animation, id, world, x, y, z, 0, 0, 0, envOf(type), visibility, false,
                Set.of(), Set.of());
    }

    /** A new, unpaused placement with a rotation and empty lists — what {@code spawn} builds. */
    public Placement(String animation, String id, String world, double x, double y, double z,
            double yaw, double pitch, double roll, Map<String, String> env,
            VisibilityMode visibility) {
        this(animation, id, world, x, y, z, yaw, pitch, roll, env, visibility, false,
                Set.of(), Set.of());
    }

    /** An unrotated placement with explicit pause flag and lists. */
    public Placement(String animation, String id, String world, double x, double y, double z,
            InstanceType type, VisibilityMode visibility, boolean paused,
            Set<String> whitelist, Set<String> blacklist) {
        this(animation, id, world, x, y, z, 0, 0, 0, envOf(type), visibility, paused,
                whitelist, blacklist);
    }

    /** The env a caller that only wants to name an instance type is really asking for. */
    private static Map<String, String> envOf(InstanceType type) {
        return Map.of(Env.TYPE, type.wire());
    }

    /**
     * How this placement instantiates, read out of {@link #env()} — {@link Env#TYPE} is the source
     * of truth, and an absent or unrecognised value reads as {@link InstanceType#SHARED}. This is
     * the placement's own layer only; where the animation layer may also carry the key, ask
     * {@link Env#typeOf(Map, Placement)} instead.
     */
    public InstanceType type() {
        return Env.typeOf(env);
    }

    /** The unique key {@code "animation/id"} for maps. */
    public String key() {
        return animation + "/" + id;
    }

    /** A copy with a different visibility mode. */
    public Placement withVisibility(VisibilityMode newVisibility) {
        return new Placement(animation, id, world, x, y, z, yaw, pitch, roll, env, newVisibility,
                paused, whitelist, blacklist);
    }

    /** A copy with a different env layer. */
    public Placement withEnv(Map<String, String> newEnv) {
        return new Placement(animation, id, world, x, y, z, yaw, pitch, roll, newEnv, visibility,
                paused, whitelist, blacklist);
    }

    /** A copy with one env key set (appended if new, replaced in place if it already existed). */
    public Placement withEnvEntry(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(env);
        updated.put(key, value);
        return withEnv(updated);
    }

    /** A copy with a different instance type — the {@link Env#TYPE} env key, written through. */
    public Placement withType(InstanceType newType) {
        return withEnvEntry(Env.TYPE, newType.wire());
    }

    /** A copy with the per-placement pause flag set or cleared. */
    public Placement withPaused(boolean newPaused) {
        return new Placement(animation, id, world, x, y, z, yaw, pitch, roll, env, visibility,
                newPaused, whitelist, blacklist);
    }

    /** A copy with a different whitelist. */
    public Placement withWhitelist(Collection<String> entries) {
        return new Placement(animation, id, world, x, y, z, yaw, pitch, roll, env, visibility,
                paused, frozen(entries), blacklist);
    }

    /** A copy with a different blacklist. */
    public Placement withBlacklist(Collection<String> entries) {
        return new Placement(animation, id, world, x, y, z, yaw, pitch, roll, env, visibility,
                paused, whitelist, frozen(entries));
    }

    /** Whichever of the two lists the flag names, for the code that treats them alike. */
    public Set<String> filter(boolean useWhitelist) {
        return useWhitelist ? whitelist : blacklist;
    }

    /** A copy with whichever of the two lists the flag names replaced. */
    public Placement withFilter(boolean useWhitelist, Collection<String> entries) {
        return useWhitelist ? withWhitelist(entries) : withBlacklist(entries);
    }

    private static Set<String> frozen(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries));
    }
}
