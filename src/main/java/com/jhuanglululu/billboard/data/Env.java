package com.jhuanglululu.billboard.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The environ layers a running instance sees, and the rules that govern them.
 *
 * <p>Billboard ABI 4 replaced the placement's {@code InstanceType type} field with a free-form
 * string map, handed to the engine as {@code MachineInstance.Config.environ} and read back by the
 * guest through {@code environ_len}/{@code environ_read}. There are three layers, merged in this
 * order at instance start:
 *
 * <ol>
 *   <li><b>animation-level</b> — {@code /billboard env} on an animation name, applying to every
 *       placement of that animation (persisted in animations.jsonl);</li>
 *   <li><b>placement-level</b> — {@code /billboard env} on an {@code animation/id} key, overriding
 *       the same keys (persisted in placements.jsonl);</li>
 *   <li><b>host built-ins</b> — the {@value #PREFIX} keys below, injected last and therefore never
 *       overridable. User keys may not start with {@value #PREFIX}; the command rejects them, so a
 *       built-in can never be shadowed even in the persisted file.</li>
 * </ol>
 *
 * <p>{@value #TYPE} is the one key the host itself interprets: it replaced {@link InstanceType} as
 * the source of truth for how a placement instantiates. Absent or unparseable it reads as
 * {@link InstanceType#SHARED} — a placement whose env someone hand-edited into nonsense still runs
 * — but {@code env set} validates it up front, so nobody gets to believe a typo took effect.
 *
 * <p>Maps are kept insertion-ordered ({@link LinkedHashMap}) in memory so {@code env list} reads
 * back in the order keys were added; {@link DataStore} writes them sorted by key, so the files
 * still diff cleanly.
 */
public final class Env {

    private Env() {}

    /** The reserved prefix of every host-injected key. User keys may not start with it. */
    public static final String PREFIX = "bb.";

    /** The one key the host interprets itself: {@code per_player} or {@code shared}. */
    public static final String TYPE = "type";

    /** An immutable, insertion-ordered copy; {@code null} and empty both yield an empty map. */
    public static Map<String, String> frozen(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /**
     * The instance type {@code env} names. Anything other than the two known tokens — including a
     * missing key — is {@link InstanceType#SHARED}: the env is free-form storage that a user (or an
     * old file) can put anything in, and a placement must still run.
     */
    public static InstanceType typeOf(Map<String, String> env) {
        String token = env.get(TYPE);
        if (token == null) {
            return InstanceType.SHARED;
        }
        try {
            return InstanceType.fromWire(token);
        } catch (IllegalArgumentException e) {
            return InstanceType.SHARED;
        }
    }

    /** The animation layer with the placement layer laid over it; built-ins are not included. */
    public static Map<String, String> merge(Map<String, String> animation,
            Map<String, String> placement) {
        Map<String, String> out = new LinkedHashMap<>(animation);
        out.putAll(placement);
        return out;
    }

    /**
     * Everything one instance's guest will see: the two user layers merged, then the built-ins
     * written over the top.
     *
     * @param animationEnv the animation-level layer
     * @param placement    the placement being started (its own layer plus its geometry)
     * @param owner        the owning player's account name for a {@code per_player} instance, or
     *                     {@code null}/empty for a shared one — the only source of
     *                     {@code bb.player}
     */
    public static Map<String, String> effective(Map<String, String> animationEnv,
            Placement placement, String owner) {
        Map<String, String> out = merge(animationEnv, placement.env());
        out.putAll(builtins(placement, owner));
        return Collections.unmodifiableMap(out);
    }

    /**
     * The host-injected {@value #PREFIX} keys for one placement, in a stable order.
     *
     * @param owner the owner name to publish as {@code bb.player}; {@code null} or empty leaves the
     *              key out entirely, which is what a {@code shared} instance (and the
     *              {@code env list} preview, which has no instance yet) wants
     */
    public static Map<String, String> builtins(Placement placement, String owner) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(PREFIX + "animation", placement.animation());
        out.put(PREFIX + "id", placement.id());
        out.put(PREFIX + TYPE, placement.type().wire());
        out.put(PREFIX + "x", number(placement.x()));
        out.put(PREFIX + "y", number(placement.y()));
        out.put(PREFIX + "z", number(placement.z()));
        out.put(PREFIX + "yaw", number(placement.yaw()));
        out.put(PREFIX + "pitch", number(placement.pitch()));
        out.put(PREFIX + "roll", number(placement.roll()));
        if (owner != null && !owner.isEmpty()) {
            out.put(PREFIX + "player", owner);
        }
        return out;
    }

    /**
     * Why {@code key} may not be set by a user, if it may not.
     *
     * @return the reason, or empty when the key is fine
     */
    public static Optional<String> rejectKey(String key) {
        if (key.isEmpty()) {
            return Optional.of("an env key cannot be empty");
        }
        if (key.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return Optional.of("keys starting with \"" + PREFIX + "\" are reserved for the host");
        }
        return Optional.empty();
    }

    /**
     * Whether {@code value} is usable for {@code key}. Only {@value #TYPE} is constrained — it is
     * the one key the host acts on, so a typo there silently changes how the placement runs; every
     * other key is opaque to the host and validating it would be inventing rules.
     */
    public static Optional<String> rejectValue(String key, String value) {
        if (!key.equals(TYPE)) {
            return Optional.empty();
        }
        try {
            InstanceType.fromWire(value);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("instance type");
        }
    }

    /** A map sorted by key, for deterministic persistence. */
    public static Map<String, String> sorted(Map<String, String> env) {
        return new TreeMap<>(env);
    }

    /**
     * How a geometry built-in is rendered: plain {@link Double#toString}, so {@code 64.0} stays
     * {@code "64.0"} and every guest {@code parse::<f64>()} round-trips it exactly.
     */
    private static String number(double value) {
        return Double.toString(value);
    }
}
