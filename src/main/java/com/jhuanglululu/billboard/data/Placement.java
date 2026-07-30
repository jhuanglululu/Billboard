package com.jhuanglululu.billboard.data;

/**
 * A user-placed instance of an animation, addressed by {@code (animation, id)}. Persisted
 * in placements.jsonl; survives restarts.
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
 */
public record Placement(String animation, String id, String world, double x, double y, double z,
        InstanceType type, VisibilityMode visibility, boolean paused) {

    /** A new, unpaused placement — every caller but the data file's reader wants this shape. */
    public Placement(String animation, String id, String world, double x, double y, double z,
            InstanceType type, VisibilityMode visibility) {
        this(animation, id, world, x, y, z, type, visibility, false);
    }

    /** The unique key {@code "animation/id"} for maps. */
    public String key() {
        return animation + "/" + id;
    }

    /** A copy with a different visibility mode. */
    public Placement withVisibility(VisibilityMode newVisibility) {
        return new Placement(animation, id, world, x, y, z, type, newVisibility, paused);
    }

    /** A copy with a different instance type. */
    public Placement withType(InstanceType newType) {
        return new Placement(animation, id, world, x, y, z, newType, visibility, paused);
    }

    /** A copy with the per-placement pause flag set or cleared. */
    public Placement withPaused(boolean newPaused) {
        return new Placement(animation, id, world, x, y, z, type, visibility, newPaused);
    }
}
