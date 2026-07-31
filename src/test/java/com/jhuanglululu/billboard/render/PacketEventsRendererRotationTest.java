package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The renderer end of placement rotation: what a spawned entity's packets would carry once the
 * origin is rotated. Nothing here sends anything (the viewer set is empty), so no server is
 * needed; the assertions read the same {@code outgoing*} views the packet builders read.
 *
 * <p>Expected values are hand-derived from the convention on {@link Rotation}, the same way
 * {@link RotationTest} derives its own.
 *
 * <p><b>What is not here, and why.</b> A <em>guest-set</em> quaternion cannot be driven through
 * {@code setRotation} from a unit test: that method builds its metadata packet before checking
 * who is watching, and PacketEvents' {@code EntityDataTypes} needs a live server behind
 * {@code PacketEvents.getAPI()} to initialize. Every case below therefore starts from the guest's
 * default (identity) quaternion, and the composition of a non-identity guest rotation with a
 * placement's is hand-derived and asserted in
 * {@link RotationTest#composingAGuestQuaternionMultipliesPlacementFirst()} — against
 * {@link Rotation#compose}, which is the one function {@code setRotation} calls.
 */
class PacketEventsRendererRotationTest {

    private static final float HALF = (float) (Math.sqrt(2) / 2);

    @Test
    void anUnrotatedPlacementJustTranslates() {
        PacketEventsRenderer r = new PacketEventsRenderer(new Origin("world", 100, 64, -50));
        r.spawnBlockDisplay(1, "minecraft:stone", 2, 3, 5);

        assertEquals(102.0, r.outgoingPosition(1)[0]);
        assertEquals(67.0, r.outgoingPosition(1)[1]);
        assertEquals(-45.0, r.outgoingPosition(1)[2]);
    }

    @Test
    void aRotatedPlacementRotatesASpawnedEntitysPosition() {
        // Origin (100, 64, -50) with yaw 90. Local (2, 3, 5): yaw 90 sends local +X to +Z and
        // local +Z to -X (RotationTest derives both), so (2, 3, 5) -> (-5, 3, 2), and the world
        // position is (100-5, 64+3, -50+2) = (95, 67, -48).
        PacketEventsRenderer r = new PacketEventsRenderer(
                new Origin("world", 100, 64, -50, new Rotation(90, 0, 0)));
        r.spawnBlockDisplay(1, "minecraft:stone", 2, 3, 5);

        assertEquals(95.0, r.outgoingPosition(1)[0], 1e-9);
        assertEquals(67.0, r.outgoingPosition(1)[1], 1e-9);
        assertEquals(-48.0, r.outgoingPosition(1)[2], 1e-9);
    }

    @Test
    void aFreshDisplayCarriesThePlacementRotationAsItsOwn() {
        // A guest that never set a rotation leaves the identity quaternion (0,0,0,1); composing
        // the placement's yaw 90 onto it must yield the placement's own quaternion,
        // (0, -sqrt(2)/2, 0, sqrt(2)/2) — a quarter turn about -Y.
        PacketEventsRenderer r = new PacketEventsRenderer(
                new Origin("world", 0, 0, 0, new Rotation(90, 0, 0)));
        r.spawnTextDisplay(4, "hi", 0, 0, 0);

        float[] q = r.outgoingRotation(4);
        assertEquals(0f, q[0], 1e-6f);
        assertEquals(-HALF, q[1], 1e-6f);
        assertEquals(0f, q[2], 1e-6f);
        assertEquals(HALF, q[3], 1e-6f);
    }

    @Test
    void anUnrotatedPlacementLeavesTheDisplayQuaternionAlone() {
        PacketEventsRenderer r = new PacketEventsRenderer(new Origin("world", 0, 0, 0));
        r.spawnItemDisplay(5, "minecraft:stick", 0, 0, 0);

        float[] q = r.outgoingRotation(5);
        assertEquals(0f, q[0]);
        assertEquals(0f, q[1]);
        assertEquals(0f, q[2]);
        assertEquals(1f, q[3]);
    }

    @Test
    void anArmorStandsYawPicksUpThePlacementsButADisplaysDoesNot() {
        PacketEventsRenderer r = new PacketEventsRenderer(
                new Origin("world", 0, 0, 0, new Rotation(90, 0, 0)));
        r.spawnArmorStand(2, 0, 0, 0);
        r.spawnBlockDisplay(3, "minecraft:stone", 0, 0, 0);

        // The stand turns with the placement: guest yaw 0 inside a yaw-90 placement faces -X.
        assertEquals(90f, r.outgoingYaw(2), 1e-4f);
        // The display must not: its orientation already comes from the composed quaternion, and
        // adding the placement yaw to its entity yaw as well would turn it twice.
        assertEquals(0f, r.outgoingYaw(3));
    }
}
