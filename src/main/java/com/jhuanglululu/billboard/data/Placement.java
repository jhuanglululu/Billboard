package com.jhuanglululu.billboard.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * @param type       per-player or shared
 * @param visibility who may see this placement
 * @param paused     whether {@code /billboard pause <id>} disabled this one placement; a paused
 *                   placement instantiates nothing, exactly like the animation-level flag but
 *                   without touching the animation's other placements
 * @param whitelist  the entries (player names and/or group ids) {@code visibility = whitelist}
 *                   consults; insertion-ordered and unmodifiable
 * @param blacklist  the entries {@code visibility = blacklist} consults
 */
public record Placement(String animation, String id, String world, double x, double y, double z,
        InstanceType type, VisibilityMode visibility, boolean paused,
        Set<String> whitelist, Set<String> blacklist) {

    public Placement {
        whitelist = frozen(whitelist);
        blacklist = frozen(blacklist);
    }

    /** A new, unpaused placement with empty lists — every caller but the data file's reader wants this shape. */
    public Placement(String animation, String id, String world, double x, double y, double z,
            InstanceType type, VisibilityMode visibility) {
        this(animation, id, world, x, y, z, type, visibility, false, Set.of(), Set.of());
    }

    /** The unique key {@code "animation/id"} for maps. */
    public String key() {
        return animation + "/" + id;
    }

    /** A copy with a different visibility mode. */
    public Placement withVisibility(VisibilityMode newVisibility) {
        return new Placement(animation, id, world, x, y, z, type, newVisibility, paused,
                whitelist, blacklist);
    }

    /** A copy with a different instance type. */
    public Placement withType(InstanceType newType) {
        return new Placement(animation, id, world, x, y, z, newType, visibility, paused,
                whitelist, blacklist);
    }

    /** A copy with the per-placement pause flag set or cleared. */
    public Placement withPaused(boolean newPaused) {
        return new Placement(animation, id, world, x, y, z, type, visibility, newPaused,
                whitelist, blacklist);
    }

    /** A copy with a different whitelist. */
    public Placement withWhitelist(Collection<String> entries) {
        return new Placement(animation, id, world, x, y, z, type, visibility, paused,
                frozen(entries), blacklist);
    }

    /** A copy with a different blacklist. */
    public Placement withBlacklist(Collection<String> entries) {
        return new Placement(animation, id, world, x, y, z, type, visibility, paused,
                whitelist, frozen(entries));
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
