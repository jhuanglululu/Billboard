package com.jhuanglululu.billboard.data;

/**
 * Per-animation persisted state: the error-{@code paused} flag, and nothing else — the
 * visibility lists live on each {@link Placement}. Mutable — {@link DataStore} owns it and
 * re-saves on change.
 */
public final class AnimationSettings {

    private boolean paused;

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }
}
