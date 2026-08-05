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
 * <p>Environ is a free-form string map handed to the engine as
 * {@code MachineInstance.Config.environ} (engine ABI 2) and read back by the guest through
 * {@code environ_len}/{@code environ_read}. There are three layers, merged in this order when an
 * instance starts:
 *
 * <ol>
 *   <li><b>animation-level</b> — {@code /billboard env set <animation> …}, applying to every
 *       placement of that animation (persisted in animations.jsonl);</li>
 *   <li><b>placement-level</b> — {@code /billboard env set <animation>/<id> …}, overriding the
 *       same keys (persisted in placements.jsonl);</li>
 *   <li><b>host built-ins</b> — the {@value #PREFIX} keys below, written last and therefore never
 *       shadowable: the placement's identity, origin and rotation, plus the resolved instance
 *       type. User keys may not start with {@value #PREFIX} (the command rejects them, and
 *       the merge order makes a hand-edited file harmless anyway) — with the single exception of
 *       {@value #TYPE}, which is the one built-in the operator is <em>meant</em> to write.</li>
 * </ol>
 *
 * <p>{@value #TYPE} is also the one key the host itself interprets: it replaced the spawn
 * grammar's {@code <type>} argument as the source of truth for how a placement instantiates.
 * Absent or unparseable it reads as {@link InstanceType#SHARED} — a placement whose env someone
 * hand-edited into nonsense still runs — but {@code env set} validates it up front, so nobody gets
 * to believe a typo took effect. The built-in layer writes the resolved value back, so the guest
 * always sees a canonical {@code shared}/{@code per_player} whatever the file says.
 *
 * <p>Maps are kept insertion-ordered ({@link LinkedHashMap}) in memory so {@code env list} reads
 * back in the order keys were added; {@link DataStore} writes them sorted by key, so the files
 * still diff cleanly.
 */
public final class Env {

    private Env() {}

    /** The reserved prefix of every host-injected key. User keys may not start with it. */
    public static final String PREFIX = "bb.";

    /**
     * The one key the host interprets itself — {@code per_player} or {@code shared} — and the one
     * {@value #PREFIX} key a user layer may carry, because setting it is how a placement becomes
     * per-player now that {@code spawn} no longer asks.
     */
    public static final String TYPE = PREFIX + "type";

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

    /**
     * How a placement instantiates once both user layers are taken into account — what every
     * consumer of the type (proximity tracking, the owner label, {@code /billboard list}) must
     * ask, since {@value #TYPE} may just as well have been set on the animation.
     */
    public static InstanceType typeOf(Map<String, String> animationEnv, Placement placement) {
        return typeOf(merge(animationEnv, placement.env()));
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
     * written over the top. It depends only on the placement — a {@code per_player} instance's
     * environ is identical to a shared one's, since no player identity is published.
     *
     * @param animationEnv the animation-level layer
     * @param placement    the placement being started (its own layer plus its geometry)
     */
    public static Map<String, String> effective(Map<String, String> animationEnv,
            Placement placement) {
        Map<String, String> out = merge(animationEnv, placement.env());
        out.putAll(builtins(placement, typeOf(out)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * The host-injected {@value #PREFIX} keys for one instance, in a stable order: which placement
     * this is, where it stands, how it is turned, and the resolved instance type.
     *
     * @param type the resolved instance type, written back canonically so the guest never has to
     *             parse whatever the user layer happened to say
     */
    public static Map<String, String> builtins(Placement placement, InstanceType type) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(PREFIX + "id", placement.id());
        out.put(PREFIX + "x", number(placement.x()));
        out.put(PREFIX + "y", number(placement.y()));
        out.put(PREFIX + "z", number(placement.z()));
        out.put(PREFIX + "yaw", number(placement.yaw()));
        out.put(PREFIX + "pitch", number(placement.pitch()));
        out.put(PREFIX + "roll", number(placement.roll()));
        out.put(TYPE, type.wire());
        return out;
    }

    /**
     * Why {@code key} may not be set by a user, if it may not. The case-insensitive test is
     * deliberate: {@code BB.x} would sort and read as a host key to every human looking at the
     * file, so it is refused even though the merge would not actually let it win.
     *
     * @return the reason, or empty when the key is fine
     */
    public static Optional<String> rejectKey(String key) {
        if (key.isEmpty()) {
            return Optional.of("an env key cannot be empty");
        }
        if (key.equals(TYPE)) {
            return Optional.empty();
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
     *
     * @return what the value failed to be (an {@code Unknown {what}: {token}} message writes it),
     *         or empty when it is fine
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
     * How a geometry built-in (origin and rotation alike) is rendered: plain
     * {@link Double#toString}, so {@code 64.0} stays {@code "64.0"} and every guest
     * {@code parse::<f64>()} round-trips it exactly.
     */
    private static String number(double value) {
        return Double.toString(value);
    }
}
