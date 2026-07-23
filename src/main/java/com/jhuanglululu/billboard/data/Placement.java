package com.jhuanglululu.billboard.data;

/**
 * A user-placed instance of an animation, addressed by {@code (animation, id)}. Persisted
 * in data.toml; survives restarts.
 *
 * @param animation  the animation name (a {@code .wasm} file stem)
 * @param id         the user-chosen placement id
 * @param world      the world name
 * @param x          origin x
 * @param y          origin y
 * @param z          origin z
 * @param type       per-player or shared
 * @param visibility who may see this placement
 */
public record Placement(String animation, String id, String world, double x, double y, double z,
        InstanceType type, VisibilityMode visibility) {

    /** The unique key {@code "animation/id"} for maps. */
    public String key() {
        return animation + "/" + id;
    }

    /** A copy with a different visibility mode. */
    public Placement withVisibility(VisibilityMode newVisibility) {
        return new Placement(animation, id, world, x, y, z, type, newVisibility);
    }

    /** A copy with a different instance type. */
    public Placement withType(InstanceType newType) {
        return new Placement(animation, id, world, x, y, z, newType, visibility);
    }
}
