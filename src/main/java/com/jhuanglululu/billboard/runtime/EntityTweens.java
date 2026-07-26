package com.jhuanglululu.billboard.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side interpolation for the entity kinds the client will not interpolate for us. Armor
 * stands and item entities have no interpolation-duration metadata, so an {@code over_ticks > 0}
 * set on their position, pose or yaw becomes a tween here: {@link #advance()} is called once per
 * tick from the render path and returns the value each live tween is at, which the renderer turns
 * into a teleport / metadata / head-look packet. No threads and no timers — the animation's own
 * tick drives it, so a paused or dead instance simply stops advancing.
 *
 * <p>Steps are linear because that is all the visual difference a per-tick packet can express;
 * easing is the guest SDK's job (it sub-steps keyframes). A tween of {@code n} ticks emits
 * {@code n} updates: tick {@code i} of {@code n} lands on {@code from + (to - from) * i / n}, so
 * the last update is exactly {@code to} and the tween is then dropped.
 *
 * <p><b>Replace, never stack.</b> Tweens are keyed by (entity, attribute, part), so a new set on
 * an attribute that is already tweening replaces it — starting from the value the old tween had
 * reached, which is what the caller passes as {@code from}. {@link #cancel} drops one entity's
 * tweens (despawn) and {@link #clear} drops all of them (instance death).
 *
 * <p><b>Thread-safe by synchronization.</b> A worker mutates this while ticking its instance, but
 * the main thread cancels and clears it — {@code RunningInstance.stop()} and {@code restart()} can
 * run while that instance's tick is still in flight, because the scheduler only clears its
 * in-flight marker afterwards on the main thread. The map stays a {@link LinkedHashMap} rather than
 * becoming concurrent so that a tick's updates keep coming out in the order the tweens started;
 * every method takes the monitor instead, which costs nothing at these sizes.
 */
public final class EntityTweens {

    /** Which attribute a tween drives; {@code part} distinguishes the six pose parts. */
    public enum Attribute {
        /** Origin-relative position: 3 components. */
        POSITION,
        /** Euler pose degrees for one armor-stand part: 3 components. */
        POSE,
        /** Body/head yaw in degrees: 1 component. */
        YAW
    }

    /**
     * One tick's worth of movement for one attribute: {@code values} are the interpolated
     * components, and {@code finished} marks the update that lands exactly on the target.
     */
    @SuppressWarnings("ArrayRecordComponent") // freshly built per tick, never compared or hashed
    public record Update(int entityId, Attribute attribute, int part, double[] values,
            boolean finished) {}

    private record Key(int entityId, Attribute attribute, int part) {}

    private static final class Tween {
        final double[] from;
        final double[] to;
        final long ticks;
        long elapsed;

        Tween(double[] from, double[] to, long ticks) {
            this.from = from;
            this.to = to;
            this.ticks = ticks;
        }
    }

    // Insertion-ordered so a tick's updates come out in the order the tweens were started.
    private final Map<Key, Tween> tweens = new LinkedHashMap<>();

    /**
     * Registers (or replaces) a tween.
     *
     * @param from  the value to start from — the renderer's current visual value
     * @param to    the guest-set target
     * @param ticks the duration; {@code <= 0} is a programming error (the caller sends instantly)
     */
    public synchronized void start(int entityId, Attribute attribute, int part, double[] from, double[] to,
            long ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("tween duration must be positive but was " + ticks);
        }
        tweens.put(new Key(entityId, attribute, part), new Tween(from.clone(), to.clone(), ticks));
    }

    /** Whether {@code attribute} of {@code entityId} is mid-tween. */
    public synchronized boolean isTweening(int entityId, Attribute attribute, int part) {
        return tweens.containsKey(new Key(entityId, attribute, part));
    }

    /** The number of live tweens (all entities). */
    public synchronized int size() {
        return tweens.size();
    }

    /**
     * Advances every tween one tick and returns what to send. Tweens that reach their target are
     * reported with {@code finished = true} and removed, so the target value is always emitted
     * exactly once.
     */
    public synchronized List<Update> advance() {
        List<Update> out = new ArrayList<>();
        tweens.entrySet().removeIf(entry -> {
            Key key = entry.getKey();
            Tween t = entry.getValue();
            t.elapsed++;
            boolean done = t.elapsed >= t.ticks;
            double[] values = new double[t.to.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = done
                        ? t.to[i] // land exactly on the target, never a rounding-error short fall
                        : t.from[i] + (t.to[i] - t.from[i]) * ((double) t.elapsed / t.ticks);
            }
            out.add(new Update(key.entityId(), key.attribute(), key.part(), values, done));
            return done;
        });
        return out;
    }

    /** Drops every tween of one entity (despawn). */
    public synchronized void cancel(int entityId) {
        tweens.keySet().removeIf(k -> k.entityId() == entityId);
    }

    /** Drops every tween (instance death). */
    public synchronized void clear() {
        tweens.clear();
    }
}
