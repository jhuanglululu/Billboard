package com.jhuanglululu.billboard.runtime;

import com.jhuanglululu.wasmachine.runtime.GuestAbort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host-side source of truth for every entity an animation spawns: its {@link EntityKind}
 * and every attribute the guest has set on it. Backs {@code is_alive} and every {@code get_*}
 * import (the SDK caches nothing and asks the host each time), and it remembers every id ever
 * spawned so the instance can despawn them all exactly once at the end.
 *
 * <p>Setters store the target value immediately — interpolation, whether client-side or a host
 * tween, is the renderer's job — which is what a subsequent getter returns. This is why
 * {@code get_position} during a tween reports the destination, not the current visual position:
 * the guest reads back what it asked for.
 *
 * <p><b>Kind checking.</b> Each attribute belongs to specific kinds, and every accessor names the
 * kinds it accepts. A mismatch ({@code set_text} on a block display), a dead id, or an unknown id
 * aborts the animation — the error philosophy, never a silent no-op.
 */
public final class EntityRegistry {

    private static final double[] IDENTITY_ROTATION = {0.0, 0.0, 0.0, 1.0};
    private static final double[] UNIT_SCALE = {1.0, 1.0, 1.0};

    /** Armor-stand pose parts, in ABI order: {@code 0 head .. 5 right leg}. */
    public static final int POSE_PARTS = 6;

    /** Armor-stand equipment slots, in ABI order: {@code 0 helmet .. 3 boots, 4 main, 5 off}. */
    public static final int EQUIPMENT_SLOTS = 6;

    /** Vanilla text-display defaults, which a fresh text display must report and render. */
    public static final long DEFAULT_LINE_WIDTH = 200;

    /** Vanilla's translucent black text background (ARGB). */
    public static final long DEFAULT_TEXT_BACKGROUND = 0x40000000L;

    /** Fully opaque text. */
    public static final long DEFAULT_TEXT_OPACITY = 255;

    private static final class Entity {
        final EntityKind kind;
        final double[] position = new double[3];
        final double[] rotation = new double[4];
        final double[] scale = new double[3];
        final double[][] poses = new double[POSE_PARTS][3];
        final String[] equipment = new String[EQUIPMENT_SLOTS];
        String block;
        String item;
        String text;
        int billboardMode;
        int displayContext;
        long textBackground;
        long textOpacity;
        long lineWidth;
        int textFlags;
        int standFlags;
        double yaw;
        boolean alive = true;

        Entity(EntityKind kind) {
            this.kind = kind;
        }
    }

    private final Map<Integer, Entity> entities = new LinkedHashMap<>();
    private int nextId = 1; // ids start at 1; 0 is reserved as a null/sentinel

    private int fresh(EntityKind kind, double x, double y, double z) {
        Entity e = new Entity(kind);
        e.position[0] = x;
        e.position[1] = y;
        e.position[2] = z;
        System.arraycopy(IDENTITY_ROTATION, 0, e.rotation, 0, 4);
        System.arraycopy(UNIT_SCALE, 0, e.scale, 0, 3);
        int id = nextId++;
        entities.put(id, e);
        return id;
    }

    /** Spawn a block display; returns its fresh id. Rotation is identity, scale is 1. */
    public int spawnBlockDisplay(String blockState, double x, double y, double z) {
        int id = fresh(EntityKind.BLOCK_DISPLAY, x, y, z);
        entities.get(id).block = blockState;
        return id;
    }

    /** Spawn an item display showing {@code item}. */
    public int spawnItemDisplay(String item, double x, double y, double z) {
        int id = fresh(EntityKind.ITEM_DISPLAY, x, y, z);
        entities.get(id).item = item;
        return id;
    }

    /**
     * Spawn a text display showing the MiniMessage string {@code text}, starting from vanilla's own
     * defaults. These matter: the renderer always writes line width and background on spawn, so the
     * client never gets a chance to apply its defaults — a zero line width would wrap every text
     * display at 0 px and show nothing.
     */
    public int spawnTextDisplay(String text, double x, double y, double z) {
        int id = fresh(EntityKind.TEXT_DISPLAY, x, y, z);
        Entity e = entities.get(id);
        e.text = text;
        e.textOpacity = DEFAULT_TEXT_OPACITY;
        e.lineWidth = DEFAULT_LINE_WIDTH;
        e.textBackground = DEFAULT_TEXT_BACKGROUND;
        return id;
    }

    /** Spawn an armor stand. Poses start at zero, equipment empty, yaw 0. */
    public int spawnArmorStand(double x, double y, double z) {
        return fresh(EntityKind.ARMOR_STAND, x, y, z);
    }

    /** Spawn a dropped-item-look entity showing {@code item}. */
    public int spawnItem(String item, double x, double y, double z) {
        int id = fresh(EntityKind.ITEM, x, y, z);
        entities.get(id).item = item;
        return id;
    }

    /** The kind of {@code id} (for renderer dispatch); aborts if it is unknown or dead. */
    public EntityKind kind(int id) {
        return live(id, "kind").kind;
    }

    /** Weak-reference liveness (host truth). Unknown ids are simply not alive. */
    public boolean isAlive(int id) {
        Entity e = entities.get(id);
        return e != null && e.alive;
    }

    private Entity live(int id, String op) {
        Entity e = entities.get(id);
        if (e == null) {
            throw new GuestAbort(op + " on unknown entity id " + id);
        }
        if (!e.alive) {
            throw new GuestAbort(op + " on despawned entity id " + id);
        }
        return e;
    }

    /** {@link #live} plus a kind check: a wrong-kind attribute op kills loudly. */
    private Entity of(int id, String op, EntityKind... kinds) {
        Entity e = live(id, op);
        for (EntityKind k : kinds) {
            if (e.kind == k) {
                return e;
            }
        }
        throw new GuestAbort(op + " on entity id " + id + ", which is " + e.kind.labelWithArticle()
                + " — " + op + " applies to " + describe(kinds));
    }

    private Entity display(int id, String op) {
        Entity e = live(id, op);
        if (!e.kind.isDisplay()) {
            throw new GuestAbort(op + " on entity id " + id + ", which is "
                    + e.kind.labelWithArticle() + " — " + op + " applies to display entities");
        }
        return e;
    }

    private static String describe(EntityKind[] kinds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) {
            sb.append(i == 0 ? "" : i == kinds.length - 1 ? " and " : ", ");
            sb.append(kinds[i].plural());
        }
        return sb.toString();
    }

    // --- shared transform ---

    /** Every kind has a position. */
    public void setPosition(int id, double x, double y, double z) {
        Entity e = live(id, "set_position");
        e.position[0] = x;
        e.position[1] = y;
        e.position[2] = z;
    }

    /**
     * Quaternion rotation is a display-entity transform: armor stands rotate through
     * {@code set_yaw} plus euler poses, and item entities have no rotation at all.
     */
    public void setRotation(int id, double qx, double qy, double qz, double qw) {
        Entity e = display(id, "set_rotation");
        e.rotation[0] = qx;
        e.rotation[1] = qy;
        e.rotation[2] = qz;
        e.rotation[3] = qw;
    }

    /** Scale is a display-entity transform (armor stands and item entities have none). */
    public void setScale(int id, double sx, double sy, double sz) {
        Entity e = display(id, "set_scale");
        e.scale[0] = sx;
        e.scale[1] = sy;
        e.scale[2] = sz;
    }

    public double[] getPosition(int id) {
        return live(id, "get_position").position.clone();
    }

    public double[] getRotation(int id) {
        return display(id, "get_rotation").rotation.clone();
    }

    public double[] getScale(int id) {
        return display(id, "get_scale").scale.clone();
    }

    // --- per-kind attributes ---

    public void setBlock(int id, String blockState) {
        of(id, "set_block", EntityKind.BLOCK_DISPLAY).block = blockState;
    }

    public String getBlock(int id) {
        return of(id, "get_block", EntityKind.BLOCK_DISPLAY).block;
    }

    /** Item displays and item entities both carry an item stack. */
    public void setItem(int id, String item) {
        of(id, "set_item", EntityKind.ITEM_DISPLAY, EntityKind.ITEM).item = item;
    }

    public String getItem(int id) {
        return of(id, "get_item", EntityKind.ITEM_DISPLAY, EntityKind.ITEM).item;
    }

    public void setDisplayContext(int id, int context) {
        of(id, "set_display_context", EntityKind.ITEM_DISPLAY).displayContext = context;
    }

    public int getDisplayContext(int id) {
        return of(id, "get_display_context", EntityKind.ITEM_DISPLAY).displayContext;
    }

    /** Billboard mode lives on the display base, so all three display kinds accept it. */
    public void setBillboardMode(int id, int mode) {
        display(id, "set_billboard_mode").billboardMode = mode;
    }

    public int getBillboardMode(int id) {
        return display(id, "get_billboard_mode").billboardMode;
    }

    public void setText(int id, String text) {
        of(id, "set_text", EntityKind.TEXT_DISPLAY).text = text;
    }

    public String getText(int id) {
        return of(id, "get_text", EntityKind.TEXT_DISPLAY).text;
    }

    public void setTextBackground(int id, long argb) {
        of(id, "set_text_background", EntityKind.TEXT_DISPLAY).textBackground = argb;
    }

    public long getTextBackground(int id) {
        return of(id, "get_text_background", EntityKind.TEXT_DISPLAY).textBackground;
    }

    public void setTextOpacity(int id, long opacity) {
        of(id, "set_text_opacity", EntityKind.TEXT_DISPLAY).textOpacity = opacity;
    }

    public long getTextOpacity(int id) {
        return of(id, "get_text_opacity", EntityKind.TEXT_DISPLAY).textOpacity;
    }

    public void setLineWidth(int id, long width) {
        of(id, "set_line_width", EntityKind.TEXT_DISPLAY).lineWidth = width;
    }

    public long getLineWidth(int id) {
        return of(id, "get_line_width", EntityKind.TEXT_DISPLAY).lineWidth;
    }

    public void setTextFlags(int id, int flags) {
        of(id, "set_text_flags", EntityKind.TEXT_DISPLAY).textFlags = flags;
    }

    public int getTextFlags(int id) {
        return of(id, "get_text_flags", EntityKind.TEXT_DISPLAY).textFlags;
    }

    public void setPose(int id, int part, double xDeg, double yDeg, double zDeg) {
        double[] pose = of(id, "set_pose", EntityKind.ARMOR_STAND).poses[requirePart(part, "set_pose")];
        pose[0] = xDeg;
        pose[1] = yDeg;
        pose[2] = zDeg;
    }

    public double[] getPose(int id, int part) {
        return of(id, "get_pose", EntityKind.ARMOR_STAND).poses[requirePart(part, "get_pose")].clone();
    }

    public void setEquipment(int id, int slot, String item) {
        of(id, "set_equipment", EntityKind.ARMOR_STAND).equipment[requireSlot(slot)] = item;
    }

    /** The item in {@code slot}, or {@code null} for an empty slot (there is no ABI getter). */
    public String getEquipment(int id, int slot) {
        return of(id, "get_equipment", EntityKind.ARMOR_STAND).equipment[requireSlot(slot)];
    }

    public void setStandFlags(int id, int flags) {
        of(id, "set_stand_flags", EntityKind.ARMOR_STAND).standFlags = flags;
    }

    public int getStandFlags(int id) {
        return of(id, "get_stand_flags", EntityKind.ARMOR_STAND).standFlags;
    }

    public void setYaw(int id, double yawDegrees) {
        of(id, "set_yaw", EntityKind.ARMOR_STAND).yaw = yawDegrees;
    }

    public double getYaw(int id) {
        return of(id, "get_yaw", EntityKind.ARMOR_STAND).yaw;
    }

    private static int requirePart(int part, String op) {
        if (part < 0 || part >= POSE_PARTS) {
            throw new GuestAbort(op + ": pose part " + part + " out of range 0.."
                    + (POSE_PARTS - 1));
        }
        return part;
    }

    private static int requireSlot(int slot) {
        if (slot < 0 || slot >= EQUIPMENT_SLOTS) {
            throw new GuestAbort("set_equipment: slot " + slot + " out of range 0.."
                    + (EQUIPMENT_SLOTS - 1));
        }
        return slot;
    }

    /**
     * Marks {@code id} despawned. Returns {@code true} if it was alive (the caller
     * should then tell the renderer), {@code false} if it was already dead (idempotent).
     *
     * @throws GuestAbort if the id was never spawned
     */
    public boolean despawn(int id) {
        Entity e = entities.get(id);
        if (e == null) {
            throw new GuestAbort("despawn on unknown entity id " + id);
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
