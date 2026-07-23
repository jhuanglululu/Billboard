package com.jhuanglululu.billboard.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host-side source of truth for every entity an animation spawns: id → block
 * state, position, rotation, scale, and liveness. Backs {@code is_alive} and every
 * {@code get_*} import (the SDK caches nothing and asks the host each time), and it
 * remembers every id ever spawned so the instance can despawn them all exactly once at
 * the end.
 *
 * <p>Setters store the target value immediately (interpolation is the renderer's job),
 * which is what a subsequent getter returns. Any {@code set_*}/{@code get_*} on a dead
 * or unknown id aborts the animation — the error philosophy.
 */
public final class EntityRegistry {

    private static final double[] IDENTITY_ROTATION = {0.0, 0.0, 0.0, 1.0};
    private static final double[] UNIT_SCALE = {1.0, 1.0, 1.0};

    private static final class Entity {
        String block;
        final double[] position = new double[3];
        final double[] rotation = new double[4];
        final double[] scale = new double[3];
        boolean alive = true;
    }

    private final Map<Integer, Entity> entities = new LinkedHashMap<>();
    private int nextId = 1; // ids start at 1; 0 is reserved as a null/sentinel

    /** Spawn a block display; returns its fresh id. Rotation is identity, scale is 1. */
    public int spawn(String blockState, double x, double y, double z) {
        int id = nextId++;
        Entity e = new Entity();
        e.block = blockState;
        e.position[0] = x;
        e.position[1] = y;
        e.position[2] = z;
        System.arraycopy(IDENTITY_ROTATION, 0, e.rotation, 0, 4);
        System.arraycopy(UNIT_SCALE, 0, e.scale, 0, 3);
        entities.put(id, e);
        return id;
    }

    /** Weak-reference liveness (host truth). Unknown ids are simply not alive. */
    public boolean isAlive(int id) {
        Entity e = entities.get(id);
        return e != null && e.alive;
    }

    private Entity live(int id, String op) {
        Entity e = entities.get(id);
        if (e == null) {
            throw new AnimationAbort(op + " on unknown entity id " + id);
        }
        if (!e.alive) {
            throw new AnimationAbort(op + " on despawned entity id " + id);
        }
        return e;
    }

    public void setPosition(int id, double x, double y, double z) {
        Entity e = live(id, "set_position");
        e.position[0] = x;
        e.position[1] = y;
        e.position[2] = z;
    }

    public void setRotation(int id, double qx, double qy, double qz, double qw) {
        Entity e = live(id, "set_rotation");
        e.rotation[0] = qx;
        e.rotation[1] = qy;
        e.rotation[2] = qz;
        e.rotation[3] = qw;
    }

    public void setScale(int id, double sx, double sy, double sz) {
        Entity e = live(id, "set_scale");
        e.scale[0] = sx;
        e.scale[1] = sy;
        e.scale[2] = sz;
    }

    public void setBlock(int id, String blockState) {
        live(id, "set_block").block = blockState;
    }

    public double[] getPosition(int id) {
        return live(id, "get_position").position.clone();
    }

    public double[] getRotation(int id) {
        return live(id, "get_rotation").rotation.clone();
    }

    public double[] getScale(int id) {
        return live(id, "get_scale").scale.clone();
    }

    public String getBlock(int id) {
        return live(id, "get_block").block;
    }

    /**
     * Marks {@code id} despawned. Returns {@code true} if it was alive (the caller
     * should then tell the renderer), {@code false} if it was already dead (idempotent).
     *
     * @throws AnimationAbort if the id was never spawned
     */
    public boolean despawn(int id) {
        Entity e = entities.get(id);
        if (e == null) {
            throw new AnimationAbort("despawn on unknown entity id " + id);
        }
        if (!e.alive) {
            return false;
        }
        e.alive = false;
        return true;
    }

    /** Every id ever spawned, in spawn order. */
    public List<Integer> allSpawned() {
        return new ArrayList<>(entities.keySet());
    }

    /** The ids still alive, in spawn order. */
    public List<Integer> liveIds() {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Entity> e : entities.entrySet()) {
            if (e.getValue().alive) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
