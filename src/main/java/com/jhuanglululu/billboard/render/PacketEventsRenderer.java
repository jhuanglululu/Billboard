package com.jhuanglululu.billboard.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustColorTransitionData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleItemStackData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.StaticSound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.jhuanglululu.wasmachine.runtime.GuestAbort;
import com.jhuanglululu.billboard.runtime.EntityKind;
import com.jhuanglululu.billboard.runtime.EntityRegistry;
import com.jhuanglululu.billboard.runtime.EntityTweens;
import com.jhuanglululu.billboard.runtime.ParticleSpec;
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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Renders an animation's entities as client-side entities using PacketEvents. Runtime registry
 * ids (small, per-instance) map to globally-unique client entity ids drawn from a private high
 * counter, so they never collide with real server entities. Packets go only to this instance's
 * current viewer set (updated via {@link #setViewers}); a player who joins mid-animation is
 * re-synced the full current state — the registry-as-source-of-truth makes that a plain replay.
 *
 * <p>PacketEvents sends are async-safe, so this is called from interpreter worker threads.
 *
 * <p><b>Entity metadata (every index/type pair below was read off the MC 26.2 protocol's
 * entity-metadata table; the Display rows independently agree with what v1 verified in-game):</b>
 * <pre>
 *  kind          idx  field                              wire type       EntityDataTypes
 *  Display        9   transform interp duration          VarInt          INT
 *  Display       10   pos/rot interp duration            VarInt          INT
 *  Display       11   translation                        Vector3         VECTOR3F
 *  Display       12   scale                              Vector3         VECTOR3F
 *  Display       13   left rotation                      Quaternion      QUATERNION
 *  Display       15   billboard constraints              Byte            BYTE
 *  BlockDisplay  23   block state                        Block State     BLOCK_STATE
 *  ItemDisplay   23   item                               Slot            ITEMSTACK
 *  ItemDisplay   24   display context                    Byte            BYTE
 *  TextDisplay   23   text                               Text Component  ADV_COMPONENT
 *  TextDisplay   24   line width                         VarInt          INT
 *  TextDisplay   25   background colour (ARGB)           VarInt          INT
 *  TextDisplay   26   text opacity                       Byte            BYTE
 *  TextDisplay   27   text flags                         Byte            BYTE
 *  Entity         0   entity flags (0x20 invisible)      Byte            BYTE
 *  Entity         5   no gravity                         Boolean         BOOLEAN
 *  ArmorStand    15   stand flags                        Byte            BYTE
 *  ArmorStand  16-21  head/body/arm/leg poses            Rotations       ROTATION
 *  Item           8   item                               Slot            ITEMSTACK
 * </pre>
 * The index/type pairing is load-bearing, not cosmetic: a slot whose client-side type is
 * BlockState/Slot/Byte but which receives an INT triggers "Invalid entity data item type for
 * field N" and disconnects the client. That is why text opacity (26) is a BYTE even though the
 * ABI carries it as an i64, and why billboard constraints (15) sit on the Display base and so
 * apply to block displays too.
 *
 * <p><b>Host tweening.</b> Armor stands and item entities have no interpolation-duration slots,
 * so an {@code overTicks > 0} position/pose/yaw set registers an {@link EntityTweens} tween and
 * {@link #tickTweens()} sends one packet per tick until it lands. Displays keep using the
 * client's own interpolation.
 *
 * <p><b>Placement rotation.</b> Everything positional this class emits goes through the
 * {@link Origin} — spawn positions, teleports (including every tween step), particle positions and
 * their offset vectors, sound positions — so a rotated placement turns the whole guest frame
 * rigidly about its origin ({@link Rotation} documents the exact convention). Display orientations
 * compose on top of the guest's: the {@code left_rotation} slot carries
 * {@code R_placement ⊗ q_guest}, never the guest quaternion alone. An unrotated placement takes
 * the identity fast path in {@link Rotation} and is byte-for-byte the pure translation it always
 * was.
 *
 * <p><b>Armor-stand limitation.</b> A placement's yaw composes honestly into an armor stand's body
 * and head yaw (Minecraft yaw is one angle about one axis, so the composition is addition), and
 * its position is rotated like everything else — but a placement's <b>pitch and roll cannot be
 * honestly applied to an armor stand</b>. A stand has no body pitch or roll on the wire; its only
 * angular state is the six per-part pose rotations, which are euler angles in each part's own
 * model frame, and folding a world-frame tilt into them would silently distort the guest's pose
 * rather than turn the stand. So the pose axes are left exactly as the guest set them: a stand
 * inside a pitched or rolled placement moves to the right place and faces the right way, but
 * stands upright. Displays, which carry a real orientation quaternion, have no such limitation.
 */
public final class PacketEventsRenderer implements Renderer {

    private static final AtomicInteger CLIENT_ID = new AtomicInteger(Integer.MAX_VALUE / 2);

    private static final int MD_ENTITY_FLAGS = 0;
    private static final int MD_NO_GRAVITY = 5;
    private static final int MD_ITEM_ENTITY_ITEM = 8;
    private static final int MD_TRANSFORM_INTERP_DURATION = 9;
    private static final int MD_POSROT_INTERP_DURATION = 10;
    private static final int MD_TRANSLATION = 11;
    private static final int MD_SCALE = 12;
    private static final int MD_LEFT_ROTATION = 13;
    private static final int MD_BILLBOARD = 15;
    private static final int MD_STAND_FLAGS = 15;
    private static final int MD_STAND_POSE_HEAD = 16; // parts 0..5 are indices 16..21
    private static final int MD_DISPLAY_CONTENT = 23; // block state / item / text, per kind
    private static final int MD_ITEM_DISPLAY_CONTEXT = 24;
    private static final int MD_TEXT_LINE_WIDTH = 24;
    private static final int MD_TEXT_BACKGROUND = 25;
    private static final int MD_TEXT_OPACITY = 26;
    private static final int MD_TEXT_FLAGS = 27;

    /** Entity-flags bit for invisibility, which {@code set_stand_flags} bit 3 maps to. */
    private static final byte ENTITY_FLAG_INVISIBLE = 0x20;

    /** {@code set_stand_flags} bits: 0 small, 1 arms, 2 no baseplate, 3 invisible. */
    private static final int STAND_FLAG_SMALL = 1;
    private static final int STAND_FLAG_ARMS = 1 << 1;
    private static final int STAND_FLAG_NO_BASEPLATE = 1 << 2;
    private static final int STAND_FLAG_INVISIBLE = 1 << 3;

    /** Protocol stand-flag bits (0x01 small, 0x04 arms, 0x08 no baseplate, 0x10 marker). */
    private static final byte PROTOCOL_STAND_SMALL = 0x01;
    private static final byte PROTOCOL_STAND_ARMS = 0x04;
    private static final byte PROTOCOL_STAND_NO_BASEPLATE = 0x08;

    private final Origin origin;
    private final EntityTweens tweens = new EntityTweens();

    public PacketEventsRenderer(Origin origin) {
        this.origin = origin;
    }

    /**
     * Everything needed to replay one entity to a late joiner, mirroring the registry's truth.
     *
     * <p><b>Target versus current.</b> For the host-tweened kinds these are two different things.
     * The {@code x/y/z}, {@code yaw} and {@code poses} fields hold the <em>target</em> the guest
     * asked for, which is what a late joiner is sent (plugin-runtime: "late-join replay sends the
     * target"). The {@code current*} fields hold where the entity visually <em>is</em>, which is
     * what a tween interpolates from and what any other packet must teleport from — otherwise a
     * yaw set in the middle of a position tween would snap the entity to its destination, and a
     * replacement tween would jump back from the old target.
     *
     * <p>Every scalar is {@code volatile} and the arrays are only touched under this object's
     * monitor: workers mutate all of it while ticking, and the main thread reads it in
     * {@code setViewers}/{@code spawnTo} while that tick may still be running.
     */
    private static final class Tracked {
        final int clientId;
        final UUID uuid;
        final EntityKind kind;
        /** Guest-set target poses, per part; replayed to late joiners. */
        final float[][] poses = new float[6][3];
        /** Where each pose part visually is; what a pose tween moves. */
        final float[][] currentPoses = new float[6][3];
        final String[] equipment = new String[6];
        volatile String content; // block state, item, or MiniMessage text, depending on kind
        volatile double x;
        volatile double y;
        volatile double z;
        volatile double currentX;
        volatile double currentY;
        volatile double currentZ;
        volatile float qx;
        volatile float qy;
        volatile float qz;
        volatile float qw = 1f;
        volatile float sx = 1f;
        volatile float sy = 1f;
        volatile float sz = 1f;
        volatile int billboardMode;
        volatile int displayContext;
        volatile long textBackground = EntityRegistry.DEFAULT_TEXT_BACKGROUND;
        volatile long textOpacity = EntityRegistry.DEFAULT_TEXT_OPACITY;
        volatile long lineWidth = EntityRegistry.DEFAULT_LINE_WIDTH;
        volatile int textFlags;
        volatile int standFlags;
        volatile float yaw;
        volatile float currentYaw;

        Tracked(int clientId, UUID uuid, EntityKind kind) {
            this.clientId = clientId;
            this.uuid = uuid;
            this.kind = kind;
        }
    }

    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();

    // --- spawning ---

    @Override
    public void spawnBlockDisplay(int id, String blockState, double x, double y, double z) {
        spawn(id, EntityKind.BLOCK_DISPLAY, blockState, x, y, z);
    }

    @Override
    public void spawnItemDisplay(int id, String item, double x, double y, double z) {
        spawn(id, EntityKind.ITEM_DISPLAY, item, x, y, z);
    }

    @Override
    public void spawnTextDisplay(int id, String text, double x, double y, double z) {
        spawn(id, EntityKind.TEXT_DISPLAY, text, x, y, z);
    }

    @Override
    public void spawnArmorStand(int id, double x, double y, double z) {
        spawn(id, EntityKind.ARMOR_STAND, null, x, y, z);
    }

    @Override
    public void spawnItem(int id, String item, double x, double y, double z) {
        spawn(id, EntityKind.ITEM, item, x, y, z);
    }

    private void spawn(int id, EntityKind kind, String content, double x, double y, double z) {
        Tracked t = new Tracked(CLIENT_ID.getAndIncrement(), UUID.randomUUID(), kind);
        t.content = content;
        store(t, x, y, z);
        entities.put(id, t);
        for (Player viewer : viewers) {
            spawnTo(viewer, t);
        }
    }

    // --- shared transform ---

    @Override
    public void setPosition(int id, double x, double y, double z, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        if (!t.kind.interpolatesOnClient() && overTicks > 0) {
            // No client interpolation for this kind: tween from where it visually is — not from the
            // previous target, which a replaced tween would jump back to — to the new target.
            tweens.start(id, EntityTweens.Attribute.POSITION, 0,
                    new double[] {t.currentX, t.currentY, t.currentZ},
                    new double[] {x, y, z}, overTicks);
            t.x = x;
            t.y = y;
            t.z = z;
            return;
        }
        store(t, x, y, z);
        if (t.kind.interpolatesOnClient()) {
            // Set the teleport (position/rotation) interpolation duration first so the client
            // smooths the move, then teleport to the origin-translated world position.
            broadcast(metadata(t.clientId, intData(MD_POSROT_INTERP_DURATION, (int) overTicks)));
        }
        broadcast(teleport(t));
    }

    /** Sets target and current together: an instant move is already where it was asked to be. */
    private static void store(Tracked t, double x, double y, double z) {
        t.x = x;
        t.y = y;
        t.z = z;
        t.currentX = x;
        t.currentY = y;
        t.currentZ = z;
    }

    @Override
    public void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        // The guest's own quaternion is what is stored (and replayed to late joiners); the
        // placement rotation is composed onto it only on the way out.
        t.qx = (float) qx;
        t.qy = (float) qy;
        t.qz = (float) qz;
        t.qw = (float) qw;
        broadcast(metadata(t.clientId,
                intData(MD_TRANSFORM_INTERP_DURATION, (int) overTicks),
                new EntityData<>(MD_LEFT_ROTATION, EntityDataTypes.QUATERNION, wireRotation(t))));
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
                new EntityData<>(MD_SCALE, EntityDataTypes.VECTOR3F,
                        new Vector3f(t.sx, t.sy, t.sz))));
    }

    // --- per-kind attributes ---

    @Override
    public void setBlock(int id, String blockState) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.content = blockState;
        broadcast(metadata(t.clientId, blockStateData(blockState)));
    }

    @Override
    public void setItem(int id, String item) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.content = item;
        broadcast(metadata(t.clientId, itemData(t.kind, item)));
    }

    @Override
    public void setDisplayContext(int id, int context) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.displayContext = context;
        broadcast(metadata(t.clientId, byteData(MD_ITEM_DISPLAY_CONTEXT, (byte) context)));
    }

    @Override
    public void setBillboardMode(int id, int mode) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.billboardMode = mode;
        broadcast(metadata(t.clientId, byteData(MD_BILLBOARD, (byte) mode)));
    }

    @Override
    public void setText(int id, String text) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.content = text;
        broadcast(metadata(t.clientId, textData(text)));
    }

    @Override
    public void setTextBackground(int id, long argb) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.textBackground = argb;
        broadcast(metadata(t.clientId, intData(MD_TEXT_BACKGROUND, (int) argb)));
    }

    @Override
    public void setTextOpacity(int id, long opacity) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.textOpacity = opacity;
        broadcast(metadata(t.clientId, byteData(MD_TEXT_OPACITY, (byte) opacity)));
    }

    @Override
    public void setLineWidth(int id, long width) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.lineWidth = width;
        broadcast(metadata(t.clientId, intData(MD_TEXT_LINE_WIDTH, (int) width)));
    }

    @Override
    public void setTextFlags(int id, int flags) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.textFlags = flags;
        broadcast(metadata(t.clientId, byteData(MD_TEXT_FLAGS, (byte) flags)));
    }

    @Override
    public void setPose(int id, int part, double xDeg, double yDeg, double zDeg, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        if (overTicks > 0) {
            synchronized (t) {
                float[] from = t.currentPoses[part];
                tweens.start(id, EntityTweens.Attribute.POSE, part,
                        new double[] {from[0], from[1], from[2]},
                        new double[] {xDeg, yDeg, zDeg}, overTicks);
                write(t.poses[part], xDeg, yDeg, zDeg); // the target the guest asked for
            }
            return;
        }
        synchronized (t) {
            write(t.poses[part], xDeg, yDeg, zDeg);
            write(t.currentPoses[part], xDeg, yDeg, zDeg);
            broadcast(metadata(t.clientId, poseData(part, t.currentPoses[part])));
        }
    }

    @Override
    public void setEquipment(int id, int slot, String item) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.equipment[slot] = item;
        broadcast(equipmentPacket(t, slot, item));
    }

    @Override
    public void setStandFlags(int id, int flags) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        t.standFlags = flags;
        // Two different slots: the stand-only flags at 15, and invisibility on the entity base.
        broadcast(metadata(t.clientId,
                byteData(MD_STAND_FLAGS, protocolStandFlags(flags)),
                byteData(MD_ENTITY_FLAGS, entityFlags(flags))));
    }

    @Override
    public void setYaw(int id, double yawDegrees, long overTicks) {
        Tracked t = entities.get(id);
        if (t == null) {
            return;
        }
        if (overTicks > 0) {
            tweens.start(id, EntityTweens.Attribute.YAW, 0, new double[] {t.currentYaw},
                    new double[] {yawDegrees}, overTicks);
            t.yaw = (float) yawDegrees;
            return;
        }
        t.yaw = (float) yawDegrees;
        t.currentYaw = (float) yawDegrees;
        sendYaw(t);
    }

    @Override
    public void despawn(int id) {
        Tracked t = entities.remove(id);
        if (t != null) {
            tweens.cancel(id);
            broadcast(new WrapperPlayServerDestroyEntities(t.clientId));
        }
    }

    // --- host tweens ---

    @Override
    public void tickTweens() {
        for (EntityTweens.Update update : tweens.advance()) {
            Tracked t = entities.get(update.entityId());
            if (t == null) {
                continue; // despawned between the set and this tick
            }
            double[] v = update.values();
            switch (update.attribute()) {
                case POSITION -> {
                    t.currentX = v[0];
                    t.currentY = v[1];
                    t.currentZ = v[2];
                    broadcast(teleportTo(t, v[0], v[1], v[2]));
                }
                case POSE -> {
                    synchronized (t) {
                        write(t.currentPoses[update.part()], v[0], v[1], v[2]);
                        broadcast(metadata(t.clientId, poseData(update.part(),
                                t.currentPoses[update.part()])));
                    }
                }
                case YAW -> {
                    t.currentYaw = (float) v[0];
                    sendYaw(t);
                }
            }
        }
    }

    // --- effects ---

    @Override
    public void playSound(String name, double x, double y, double z, int category, double volume,
            double pitch) {
        // StaticSound wraps the id without consulting any registry: an unknown sound is simply
        // nothing on the client, the one documented exception to the error philosophy.
        ResourceLocation location = soundLocation(name);
        if (location == null) {
            return;
        }
        broadcast(new WrapperPlayServerSoundEffect(
                new StaticSound(location, null),
                SoundCategory.fromId(category),
                worldPoint(x, y, z),
                (float) volume, (float) pitch, 0L));
    }

    /**
     * The emission point is a point, so it is rotated with the origin; the offset triple is a
     * direction, so it is rotated <em>without</em> it. For {@code count > 0} that triple is a
     * per-axis spread rather than a vector — rotating it turns an axis-aligned box into the
     * component-wise magnitudes of a turned one, which is the closest a three-scalar spread can
     * come to following the placement, and the sign it may pick up is harmless because the client
     * multiplies each component by a symmetric gaussian.
     */
    @Override
    public void emitParticle(ParticleSpec.Emission e) {
        Rotation r = origin.rotation();
        broadcast(new WrapperPlayServerParticle(particle(e.particle()), true,
                worldPoint(e.x(), e.y(), e.z()),
                new Vector3f((float) r.rotatedX(e.offsetX(), e.offsetY(), e.offsetZ()),
                        (float) r.rotatedY(e.offsetX(), e.offsetY(), e.offsetZ()),
                        (float) r.rotatedZ(e.offsetX(), e.offsetY(), e.offsetZ())),
                (float) e.speed(), e.count()));
    }

    private static Particle<?> particle(ParticleSpec spec) {
        return switch (spec) {
            case ParticleSpec.Named n -> {
                ParticleType<?> type = ParticleTypes.getByName(n.name());
                if (type == null) {
                    throw new GuestAbort("unknown particle \"" + n.name() + "\"");
                }
                yield new Particle<>(type);
            }
            case ParticleSpec.Dust d -> new Particle<>(ParticleTypes.DUST, new ParticleDustData(
                    (float) d.size(), (float) d.red(), (float) d.green(), (float) d.blue()));
            case ParticleSpec.DustTransition d ->
                    new Particle<>(ParticleTypes.DUST_COLOR_TRANSITION,
                            new ParticleDustColorTransitionData((float) d.size(),
                                    (float) d.fromRed(), (float) d.fromGreen(), (float) d.fromBlue(),
                                    (float) d.toRed(), (float) d.toGreen(), (float) d.toBlue()));
            case ParticleSpec.Block b -> new Particle<>(ParticleTypes.BLOCK,
                    new ParticleBlockStateData(SpigotConversionUtil.fromBukkitBlockData(
                            Bukkit.createBlockData(b.blockState()))));
            case ParticleSpec.Item i -> new Particle<>(ParticleTypes.ITEM,
                    new ParticleItemStackData(protocolItem(i.item())));
        };
    }

    // --- viewers ---

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

    /** Destroy every entity for every viewer (instance stop / cleanup) and drop all tweens. */
    public void destroyAll() {
        int[] ids = entities.values().stream().mapToInt(t -> t.clientId).toArray();
        entities.clear();
        tweens.clear();
        if (ids.length > 0) {
            broadcast(new WrapperPlayServerDestroyEntities(ids));
        }
    }

    /**
     * Replays one entity's whole current state to one viewer. Late joiners get tween targets, not
     * intermediate values: the tween keeps running for everyone else, it just starts the new
     * viewer at the end state instead of desyncing them mid-flight.
     */
    private void spawnTo(Player viewer, Tracked t) {
        float yaw = wireYaw(t, t.yaw);
        sendTo(viewer, new WrapperPlayServerSpawnEntity(t.clientId, Optional.of(t.uuid),
                entityType(t.kind), worldPos(t), 0f, yaw, yaw, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>();
        switch (t.kind) {
            case BLOCK_DISPLAY -> {
                data.add(blockStateData(t.content));
                addDisplayTransform(data, t);
            }
            case ITEM_DISPLAY -> {
                data.add(itemData(t.kind, t.content));
                data.add(byteData(MD_ITEM_DISPLAY_CONTEXT, (byte) t.displayContext));
                addDisplayTransform(data, t);
            }
            case TEXT_DISPLAY -> {
                data.add(textData(t.content));
                data.add(intData(MD_TEXT_LINE_WIDTH, (int) t.lineWidth));
                data.add(intData(MD_TEXT_BACKGROUND, (int) t.textBackground));
                data.add(byteData(MD_TEXT_OPACITY, (byte) t.textOpacity));
                data.add(byteData(MD_TEXT_FLAGS, (byte) t.textFlags));
                addDisplayTransform(data, t);
            }
            case ARMOR_STAND -> {
                data.add(byteData(MD_STAND_FLAGS, protocolStandFlags(t.standFlags)));
                data.add(byteData(MD_ENTITY_FLAGS, entityFlags(t.standFlags)));
                data.add(noGravity());
                synchronized (t) {
                    for (int part = 0; part < t.poses.length; part++) {
                        data.add(poseData(part, t.poses[part])); // the target, not mid-tween values
                    }
                }
            }
            case ITEM -> {
                data.add(itemData(t.kind, t.content));
                data.add(noGravity());
            }
        }
        sendTo(viewer, metadata(t.clientId, data));
        if (t.kind == EntityKind.ARMOR_STAND) {
            sendTo(viewer, new WrapperPlayServerEntityHeadLook(t.clientId, yaw));
            for (int slot = 0; slot < t.equipment.length; slot++) {
                if (t.equipment[slot] != null) {
                    sendTo(viewer, equipmentPacket(t, slot, t.equipment[slot]));
                }
            }
        }
    }

    private void addDisplayTransform(List<EntityData<?>> data, Tracked t) {
        data.add(new EntityData<>(MD_TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(0f, 0f, 0f)));
        data.add(new EntityData<>(MD_SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f(t.sx, t.sy, t.sz)));
        data.add(new EntityData<>(MD_LEFT_ROTATION, EntityDataTypes.QUATERNION, wireRotation(t)));
        data.add(byteData(MD_BILLBOARD, (byte) t.billboardMode));
    }

    private static EntityType entityType(EntityKind kind) {
        return switch (kind) {
            case BLOCK_DISPLAY -> EntityTypes.BLOCK_DISPLAY;
            case ITEM_DISPLAY -> EntityTypes.ITEM_DISPLAY;
            case TEXT_DISPLAY -> EntityTypes.TEXT_DISPLAY;
            case ARMOR_STAND -> EntityTypes.ARMOR_STAND;
            case ITEM -> EntityTypes.ITEM;
        };
    }

    // --- packet + metadata builders ---

    private static void write(float[] triple, double x, double y, double z) {
        triple[0] = (float) x;
        triple[1] = (float) y;
        triple[2] = (float) z;
    }

    /** Armor-stand poses are euler degrees end-to-end: the wire type is Rotations (Vector3f). */
    private static EntityData<?> poseData(int part, float[] triple) {
        return new EntityData<>(MD_STAND_POSE_HEAD + part, EntityDataTypes.ROTATION,
                new Vector3f(triple[0], triple[1], triple[2]));
    }

    /** ABI stand-flag bits translated to the protocol's layout (they are not the same bits). */
    private static byte protocolStandFlags(int flags) {
        byte out = 0;
        if ((flags & STAND_FLAG_SMALL) != 0) {
            out |= PROTOCOL_STAND_SMALL;
        }
        if ((flags & STAND_FLAG_ARMS) != 0) {
            out |= PROTOCOL_STAND_ARMS;
        }
        if ((flags & STAND_FLAG_NO_BASEPLATE) != 0) {
            out |= PROTOCOL_STAND_NO_BASEPLATE;
        }
        return out;
    }

    /** Invisibility is not a stand flag — it is the entity-base flags byte, bit 0x20. */
    private static byte entityFlags(int standFlags) {
        return (standFlags & STAND_FLAG_INVISIBLE) != 0 ? ENTITY_FLAG_INVISIBLE : 0;
    }

    private static WrapperPlayServerEntityEquipment equipmentPacket(Tracked t, int slot,
            String item) {
        return new WrapperPlayServerEntityEquipment(t.clientId,
                List.of(new Equipment(equipmentSlot(slot), protocolItem(item))));
    }

    /** ABI slots {@code 0 helmet .. 3 boots, 4 main hand, 5 off hand}. */
    private static EquipmentSlot equipmentSlot(int slot) {
        return switch (slot) {
            case 0 -> EquipmentSlot.HELMET;
            case 1 -> EquipmentSlot.CHEST_PLATE;
            case 2 -> EquipmentSlot.LEGGINGS;
            case 3 -> EquipmentSlot.BOOTS;
            case 4 -> EquipmentSlot.MAIN_HAND;
            default -> EquipmentSlot.OFF_HAND;
        };
    }

    /** Body yaw and head yaw both move: a turned body with a fixed head reads as broken. */
    private void sendYaw(Tracked t) {
        broadcast(teleport(t));
        broadcast(new WrapperPlayServerEntityHeadLook(t.clientId, wireYaw(t, t.currentYaw)));
    }

    /**
     * The yaw that goes on the wire for a guest-set yaw. Armor stands turn with the placement, so
     * their yaw carries the placement's; the other kinds do not use the entity yaw field for
     * anything the guest can see (displays orient by the {@code left_rotation} quaternion, which
     * already carries the placement rotation, and item entities have no visible facing), so
     * folding it in there would double-rotate a display.
     */
    private float wireYaw(Tracked t, float guestYaw) {
        return t.kind == EntityKind.ARMOR_STAND ? origin.rotation().composeYaw(guestYaw) : guestYaw;
    }

    /** The guest's display rotation with the placement's composed onto it: {@code R ⊗ q_guest}. */
    private Quaternion4f wireRotation(Tracked t) {
        float[] q = origin.rotation().compose(t.qx, t.qy, t.qz, t.qw);
        return new Quaternion4f(q[0], q[1], q[2], q[3]);
    }

    // --- what one entity's next packet would carry ---
    //
    // Package-private views of the three values the placement rotation changes, so the mapping
    // from guest values to wire values can be asserted without a live server behind PacketEvents.
    // They read the same fields the packet builders above read, through the same helpers.

    /** The absolute world position of {@code id}'s target, as {@code {x, y, z}}. */
    double[] outgoingPosition(int id) {
        Tracked t = entities.get(id);
        return new double[] {origin.worldX(t.x, t.y, t.z), origin.worldY(t.x, t.y, t.z),
            origin.worldZ(t.x, t.y, t.z)};
    }

    /** The {@code left_rotation} quaternion of {@code id}, as {@code {x, y, z, w}}. */
    float[] outgoingRotation(int id) {
        return origin.rotation().compose(entities.get(id).qx, entities.get(id).qy,
                entities.get(id).qz, entities.get(id).qw);
    }

    /** The yaw {@code id}'s spawn/teleport packets carry. */
    float outgoingYaw(int id) {
        Tracked t = entities.get(id);
        return wireYaw(t, t.yaw);
    }

    /** One origin-relative point mapped into the world: {@code origin + R · local}. */
    private Vector3d worldPoint(double x, double y, double z) {
        return new Vector3d(origin.worldX(x, y, z), origin.worldY(x, y, z), origin.worldZ(x, y, z));
    }

    /** A teleport to where the entity visually is — never to a tween's destination. */
    private WrapperPlayServerEntityTeleport teleport(Tracked t) {
        return teleportTo(t, t.currentX, t.currentY, t.currentZ);
    }

    private WrapperPlayServerEntityTeleport teleportTo(Tracked t, double x, double y, double z) {
        return new WrapperPlayServerEntityTeleport(t.clientId, worldPoint(x, y, z),
                wireYaw(t, t.currentYaw), 0f, false);
    }

    /**
     * The absolute world position a late joiner is sent: the placement origin plus the entity's
     * <em>target</em> coordinates, so a running tween finishes for everyone instead of desyncing
     * the newcomer mid-flight.
     */
    private Vector3d worldPos(Tracked t) {
        return worldPoint(t.x, t.y, t.z);
    }

    private static EntityData<?> intData(int index, int value) {
        return new EntityData<>(index, EntityDataTypes.INT, value);
    }

    private static EntityData<?> byteData(int index, byte value) {
        return new EntityData<>(index, EntityDataTypes.BYTE, value);
    }

    /**
     * Entity base index 5, no gravity (BOOLEAN). Armor stands and item entities are packet-only, so
     * the server never ticks them — but the client does, and it will happily let an item entity fall
     * between our packets. Every position these kinds have comes from us, so gravity must be off.
     */
    private static EntityData<?> noGravity() {
        return new EntityData<>(MD_NO_GRAVITY, EntityDataTypes.BOOLEAN, true);
    }

    /** Field 23 on a block display is a BLOCK_STATE slot (the global id as an Integer), not an INT. */
    private static EntityData<?> blockStateData(String blockState) {
        return new EntityData<>(MD_DISPLAY_CONTENT, EntityDataTypes.BLOCK_STATE,
                SpigotConversionUtil.fromBukkitBlockData(Bukkit.createBlockData(blockState))
                        .getGlobalId());
    }

    /** Item displays carry their stack at 23, item entities at 8; both slots are Slot-typed. */
    private static EntityData<?> itemData(EntityKind kind, String item) {
        int index = kind == EntityKind.ITEM ? MD_ITEM_ENTITY_ITEM : MD_DISPLAY_CONTENT;
        return new EntityData<>(index, EntityDataTypes.ITEMSTACK, protocolItem(item));
    }

    /**
     * Field 23 on a text display is a Text Component slot, so the parsed Component goes on it.
     * Parsed by {@link GuestText} — the same instance {@link PaperContentValidator} validated with,
     * so anything the runtime accepted renders here.
     */
    private static EntityData<?> textData(String miniMessage) {
        Component parsed = GuestText.parse(miniMessage);
        return new EntityData<>(MD_DISPLAY_CONTENT, EntityDataTypes.ADV_COMPONENT, parsed);
    }

    /**
     * Parses the vanilla component format with the server's own item factory, then converts to the
     * protocol stack. Validation already happened in the runtime, so a failure here means the two
     * parsers disagree — still a kill, never a silently empty hand.
     */
    private static ItemStack protocolItem(String item) {
        try {
            return SpigotConversionUtil.fromBukkitItemStack(
                    Bukkit.getItemFactory().createItemStack(item));
        } catch (IllegalArgumentException e) {
            throw new GuestAbort("invalid item \"" + item + "\": " + e.getMessage());
        }
    }

    /**
     * A sound id as a resource location, or {@code null} if it is not even well-formed (spaces,
     * capitals, …). Sounds never kill, so an unusable id is dropped rather than reported: the
     * client would have resolved it to nothing anyway.
     */
    private static ResourceLocation soundLocation(String name) {
        try {
            return new ResourceLocation(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static WrapperPlayServerEntityMetadata metadata(int clientId, EntityData<?>... data) {
        return metadata(clientId, List.of(data));
    }

    private static WrapperPlayServerEntityMetadata metadata(int clientId, List<EntityData<?>> data) {
        return new WrapperPlayServerEntityMetadata(clientId, new ArrayList<>(data));
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
