package com.jhuanglululu.billboard.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-animation persisted state: the error-{@code paused} flag and the animation-level
 * {@linkplain Env environ} layer — the visibility lists live on each {@link Placement}. Mutable —
 * {@link DataStore} owns it and re-saves on change.
 *
 * <p>The env map is the bottom layer of the three the guest ends up seeing: every placement of this
 * animation starts from it, overrides what it likes with its own layer, and the host writes the
 * {@code bb.*} built-ins over both. Insertion-ordered so {@code env list} reads back in the order
 * the operator typed; {@link DataStore} persists it sorted by key.
 */
public final class AnimationSettings {

    private boolean paused;
    private final Map<String, String> env = new LinkedHashMap<>();

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** The live, mutable animation-level env layer; callers mutate it and then save the store. */
    public Map<String, String> env() {
        return env;
    }
}
