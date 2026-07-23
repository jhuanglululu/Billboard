package com.jhuanglululu.billboard.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.jhuanglululu.billboard.runtime.Renderer;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Renders an animation's entities as client-side block-display entities using PacketEvents.
 * Runtime registry ids (small, per-instance) map to globally-unique client entity ids drawn
 * from a private high counter, so they never collide with real server entities. Packets go
 * only to this instance's current viewer set (updated via {@link #setViewers}); a player who
 * joins mid-animation is re-synced the full current state — the registry-as-source-of-truth
 * makes that a plain replay.
 *
 * <p>PacketEvents sends are async-safe, so this is called from interpreter worker threads.
 *
 * <p><b>Display-entity metadata (verified against MC 26.2 + PacketEvents 2.13.0):</b>
 * <pre>
 *  idx  field                                    EntityDataType   value class
 *   9   transformation interpolation duration    INT              Integer
 *  10   pos/rot (teleport) interpolation duration INT             Integer
 *  11   translation                              VECTOR3F         Vector3f
 *  12   scale                                    VECTOR3F         Vector3f
 *  13   left rotation                            QUATERNION       Quaternion4f
 *  23   block state (BlockDisplay)               BLOCK_STATE      Integer (global id)
 * </pre>
 * Index 23 must be BLOCK_STATE, not INT: the client's slot is BlockState-typed, and sending an
 * INT triggers a "Invalid entity data item type for field 23" protocol error / disconnect.
 */
public final class PacketEventsRenderer implements Renderer {

    private static final AtomicInteger CLIENT_ID = new AtomicInteger(Integer.MAX_VALUE / 2);

    private static final int MD_TRANSFORM_INTERP_DURATION = 9;
    private static final int MD_POSROT_INTERP_DURATION = 10;
    private static final int MD_TRANSLATION = 11;
    private static final int MD_SCALE = 12;
    private static final int MD_LEFT_ROTATION = 13;
    private static final int MD_BLOCK_STATE = 23;

    private final Origin origin;

    public PacketEventsRenderer(Origin origin) {
        this.origin = origin;
    }

    private static final class Tracked {
        final int clientId;
        final UUID uuid;
        String block;
        double x;
        double y;
        double z;
        float qx;
        float qy;
        float qz;
        float qw = 1f;
        float sx = 1f;
        float sy = 1f;
        float sz = 1f;

        Tracked(int clientId, UUID uuid) {
            this.clientId = clientId;
            this.uuid = uuid;
        }
    }

    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();

    @Override
    public void spawnBlockDisplay(int id, String blockState, double x, double y, double z) {
        Tracked t = new Tracked(CLIENT_ID.getAndIncrement(), UUID.randomUUID());
        t.block = blockState;
        t.x = x;
        t.y = y;
        t.z = z;
        entities.put(id, t);
        for (Player viewer : viewers) {
            spawnTo(viewer, t);
        }
    }

    @Override
    public void setPosition(int id, double x, double y, double z, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.x = x;
        t.y = y;
        t.z = z;
        // Set the teleport (position/rotation) interpolation duration first so the client
        // smooths the move, then teleport to the origin-translated world position.
        broadcast(metadata(t.clientId, intData(MD_POSROT_INTERP_DURATION, (int) overTicks)));
        broadcast(new WrapperPlayServerEntityTeleport(t.clientId, worldPos(t), 0f, 0f, false));
    }

    @Override
    public void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.qx = (float) qx;
        t.qy = (float) qy;
        t.qz = (float) qz;
        t.qw = (float) qw;
        broadcast(metadata(t.clientId,
                intData(MD_TRANSFORM_INTERP_DURATION, (int) overTicks),
                new EntityData<>(MD_LEFT_ROTATION, EntityDataTypes.QUATERNION,
                        new Quaternion4f(t.qx, t.qy, t.qz, t.qw))));
    }

    @Override
    public void setScale(int id, double sx, double sy, double sz, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.sx = (float) sx;
        t.sy = (float) sy;
        t.sz = (float) sz;
        broadcast(metadata(t.clientId,
                intData(MD_TRANSFORM_INTERP_DURATION, (int) overTicks),
                new EntityData<>(MD_SCALE, EntityDataTypes.VECTOR3F, new Vector3f(t.sx, t.sy, t.sz))));
    }

    @Override
    public void setBlock(int id, String blockState) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.block = blockState;
        broadcast(metadata(t.clientId, blockStateData(blockStateId(blockState))));
    }

    @Override
    public void despawn(int id) {
        Tracked t = entities.remove(id);
        if (t != null) {
            broadcast(new WrapperPlayServerDestroyEntities(t.clientId));
        }
    }

    /** Replace the viewer set: newly-added viewers get a full replay, removed ones get a cleanup. */
    public void setViewers(Set<Player> newViewers) {
        for (Player p : newViewers) {
            if (viewers.add(p)) {
                for (Tracked t : entities.values()) {
                    spawnTo(p, t);
                }
            }
        }
        viewers.removeIf(p -> {
            if (newViewers.contains(p)) {
                return false;
            }
            int[] ids = entities.values().stream().mapToInt(t -> t.clientId).toArray();
            if (ids.length > 0) {
                sendTo(p, new WrapperPlayServerDestroyEntities(ids));
            }
            return true;
        });
    }

    /** Destroy every entity for every viewer (instance stop / cleanup). */
    public void destroyAll() {
        int[] ids = entities.values().stream().mapToInt(t -> t.clientId).toArray();
        entities.clear();
        if (ids.length > 0) {
            broadcast(new WrapperPlayServerDestroyEntities(ids));
        }
    }

    private void spawnTo(Player viewer, Tracked t) {
        sendTo(viewer, new WrapperPlayServerSpawnEntity(t.clientId, Optional.of(t.uuid),
                EntityTypes.BLOCK_DISPLAY, worldPos(t), 0f, 0f, 0f, 0, Optional.empty()));
        sendTo(viewer, metadata(t.clientId,
                blockStateData(blockStateId(t.block)),
                new EntityData<>(MD_TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(0f, 0f, 0f)),
                new EntityData<>(MD_SCALE, EntityDataTypes.VECTOR3F, new Vector3f(t.sx, t.sy, t.sz)),
                new EntityData<>(MD_LEFT_ROTATION, EntityDataTypes.QUATERNION,
                        new Quaternion4f(t.qx, t.qy, t.qz, t.qw))));
    }

    /** The entity's absolute world position: the placement origin plus its relative coords. */
    private Vector3d worldPos(Tracked t) {
        return new Vector3d(origin.worldX(t.x), origin.worldY(t.y), origin.worldZ(t.z));
    }

    private static EntityData<?> intData(int index, int value) {
        return new EntityData<>(index, EntityDataTypes.INT, value);
    }

    /** Field 23 is a BLOCK_STATE slot (the global id as an Integer), not an INT. */
    private static EntityData<?> blockStateData(int globalId) {
        return new EntityData<>(MD_BLOCK_STATE, EntityDataTypes.BLOCK_STATE, globalId);
    }

    private static WrapperPlayServerEntityMetadata metadata(int clientId, EntityData<?>... data) {
        List<EntityData<?>> list = new ArrayList<>(data.length);
        for (EntityData<?> d : data) {
            list.add(d);
        }
        return new WrapperPlayServerEntityMetadata(clientId, list);
    }

    private static int blockStateId(String blockState) {
        return SpigotConversionUtil.fromBukkitBlockData(Bukkit.createBlockData(blockState)).getGlobalId();
    }

    private void broadcast(PacketWrapper<?> wrapper) {
        for (Player viewer : viewers) {
            sendTo(viewer, wrapper);
        }
    }

    private static void sendTo(Player viewer, PacketWrapper<?> wrapper) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
    }
}
