package com.jhuanglululu.billboard.data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-animation persisted state: the error-{@code paused} flag plus the whitelist and
 * blacklist (each holding player names and/or group ids). Mutable — {@link DataStore}
 * owns it and re-saves on change.
 */
public final class AnimationSettings {

    private boolean paused;
    private final Set<String> whitelist = new LinkedHashSet<>();
    private final Set<String> blacklist = new LinkedHashSet<>();

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** The live whitelist set (player names / group ids). */
    public Set<String> whitelist() {
        return whitelist;
    }

    /** The live blacklist set (player names / group ids). */
    public Set<String> blacklist() {
        return blacklist;
    }
}
