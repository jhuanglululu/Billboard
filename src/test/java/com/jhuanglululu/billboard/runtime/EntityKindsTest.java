package com.jhuanglululu.billboard.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_BILLBOARD_MODE;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_DISPLAY_CONTEXT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_ITEM;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_ITEM_LEN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_LINE_WIDTH;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_TEXT_BACKGROUND;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_POSE;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_STAND_FLAGS;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_TEXT_OPACITY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.GET_YAW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SCRATCH;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_BILLBOARD_MODE;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_BLOCK;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_DISPLAY_CONTEXT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_EQUIPMENT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_ITEM;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_LINE_WIDTH;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_POSE;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_POSITION;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_ROTATION;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_SCALE;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_STAND_FLAGS;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_TEXT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_TEXT_OPACITY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SET_YAW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SPAWN_ARMOR_STAND;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SPAWN_BLOCK_DISPLAY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SPAWN_ITEM;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SPAWN_ITEM_DISPLAY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SPAWN_TEXT_DISPLAY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncRun;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The v2 entity kinds through real WASM: each spawner reaches the renderer with the right kind,
 * attributes round-trip through the registry, and every wrong-kind attribute op kills the animation
 * instead of being ignored.
 *
 * <p>Ids are deterministic — the registry hands them out from 1 in spawn order — so a program that
 * spawns one entity always addresses it as id 1.
 */
class EntityKindsTest {

    private static final int A = 0;
    private static final int Y = 24;

    /** Spawns one entity of each kind, in registry-id order 1..5. */
    private static P spawnAllKinds() {
        return new P()
                .i32(0).i32(1).xyz(1, 2, 3).call(SPAWN_BLOCK_DISPLAY).drop()   // id 1
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()    // id 2
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()    // id 3
                .xyz(4, 5, 6).call(SPAWN_ARMOR_STAND).drop()                   // id 4
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM).drop();           // id 5
    }

    @Test
    void everyKindReachesTheRendererWithItsOwnSpawnCall() {
        RecordingRenderer renderer = new RecordingRenderer();
        BillboardRun.rendered(spawnAllKinds(), renderer).assertFinished();

        assertEquals(1, renderer.count("spawn"));            // block display
        assertEquals(1, renderer.count("spawnItemDisplay"));
        assertEquals(1, renderer.count("spawnTextDisplay"));
        assertEquals(1, renderer.count("spawnArmorStand"));
        assertEquals(1, renderer.count("spawnItem"));
        // Ids are handed out in spawn order, and coordinates pass through origin-relative.
        assertEquals(1, renderer.first("spawn").id());
        assertEquals(4, renderer.first("spawnArmorStand").id());
        assertEquals(6.0, renderer.first("spawnArmorStand").nums()[2]);
    }

    @Test
    void spawnersPassTheirContentString() {
        RecordingRenderer renderer = new RecordingRenderer();
        // "AB" as the item string, "CDE" as the text.
        P main = new P()
                .i32(0).i32(2).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()
                .i32(2).i32(3).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop();
        BillboardRun.rendered(main, renderer).assertFinished();

        assertEquals("AB", renderer.first("spawnItemDisplay").text());
        assertEquals("CDE", renderer.first("spawnTextDisplay").text());
    }

    @Test
    void textAttributesRoundTripThroughTheRegistry() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()
                .i32(1).i64(128).call(SET_TEXT_OPACITY)
                .i32(1).i64(240).call(SET_LINE_WIDTH)
                .i32(1).call(GET_TEXT_OPACITY).ifEqI64(128, new P().log(A))
                .i32(1).call(GET_LINE_WIDTH).ifEqI64(240, new P().log(Y));
        SyncRun.Result result = BillboardRun.rendered(main, renderer);

        assertEquals("AY", result.assertFinished().trace());
        assertEquals(128, renderer.first("setTextOpacity").over());
        assertEquals(240, renderer.first("setLineWidth").over());
    }

    @Test
    void billboardModeAppliesToBlockDisplaysToo() {
        // The billboard slot is on the Display base, so a block display must accept it.
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_BLOCK_DISPLAY).drop()
                .i32(1).i32(3).call(SET_BILLBOARD_MODE)
                .i32(1).call(GET_BILLBOARD_MODE).ifEq(3, new P().log(A));

        assertEquals("A", BillboardRun.run(main).assertFinished().trace());
    }

    @Test
    void itemAttributesWorkOnBothItemKinds() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()   // id 1
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM).drop()           // id 2
                .i32(1).i32(1).i32(2).call(SET_ITEM)                          // "BC" on the display
                .i32(2).i32(3).i32(1).call(SET_ITEM)                          // "D" on the entity
                .i32(1).call(GET_ITEM_LEN).ifEq(2, new P().log(A))
                .i32(1).i32(SCRATCH).call(GET_ITEM);
        SyncRun.Result result = BillboardRun.rendered(main, renderer);

        assertEquals("A", result.assertFinished().trace());
        assertEquals("BC", renderer.of("setItem").get(0).text());
        assertEquals("D", renderer.of("setItem").get(1).text());
    }

    @Test
    void armorStandPoseYawAndFlagsRoundTrip() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(2).xyz(10, 20, 30).i64(0).call(SET_POSE)     // left arm, instant
                .i32(1).f64(45.5).i64(0).call(SET_YAW)
                .i32(1).i32(0b1011).call(SET_STAND_FLAGS)
                .i32(1).call(GET_YAW).ifEqF64(45.5, new P().log(A))
                .i32(1).call(GET_STAND_FLAGS).ifEq(0b1011, new P().log(Y))
                .i32(1).i32(2).i32(SCRATCH).call(GET_POSE);
        SyncRun.Result result = BillboardRun.rendered(main, renderer);

        assertEquals("AY", result.assertFinished().trace());
        RecordingRenderer.Event pose = renderer.first("setPose");
        assertEquals(2, (int) pose.nums()[0]);
        assertEquals(30.0, pose.nums()[3]);
        assertEquals(45.5, renderer.first("setYaw").nums()[0]);
    }

    @Test
    void equipmentSlotsReachTheRenderer() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(0).i32(0).i32(1).call(SET_EQUIPMENT)   // helmet = "A"
                .i32(1).i32(5).i32(1).i32(1).call(SET_EQUIPMENT);  // off hand = "B"
        BillboardRun.rendered(main, renderer).assertFinished();

        assertEquals(0, (int) renderer.of("setEquipment").get(0).nums()[0]);
        assertEquals("A", renderer.of("setEquipment").get(0).text());
        assertEquals(5, (int) renderer.of("setEquipment").get(1).nums()[0]);
        assertEquals("B", renderer.of("setEquipment").get(1).text());
    }

    @Test
    void positionAppliesToEveryKind() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = new P().append(spawnAllKinds());
        for (int id = 1; id <= 5; id++) {
            main.i32(id).xyz(id, 0, 0).i64(2).call(SET_POSITION);
        }
        BillboardRun.rendered(main, renderer).assertFinished();

        assertEquals(5, renderer.count("setPosition"));
        assertEquals(2L, renderer.first("setPosition").over());
    }

    @Test
    void displayContextAcceptsFixedTheNinthValue() {
        // Vanilla ItemDisplayTransform has nine values, NONE=0 .. FIXED=8, and Fixed is the normal
        // choice for a billboard-style item display. Rejecting 8 would kill the common case.
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()
                .i32(1).i32(8).call(SET_DISPLAY_CONTEXT)
                .i32(1).call(GET_DISPLAY_CONTEXT).ifEq(8, new P().log(A));

        assertEquals("A", BillboardRun.run(main).assertFinished().trace());
    }

    @Test
    void displayContextBeyondFixedStillKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()
                .i32(1).i32(9).call(SET_DISPLAY_CONTEXT);
        BillboardRun.run(main).assertKilled("set_display_context", "display context 9 out of range 0..8");
    }

    @Test
    void textDisplaysStartFromTheVanillaDefaults() {
        // A text display must come up with the client's own defaults, because spawnTo always emits
        // both fields: line width 200 px and the 0x40000000 translucent background. Zero would make
        // every text display wrap at 0 px, i.e. show nothing.
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()
                .i32(1).call(GET_LINE_WIDTH).ifEqI64(200, new P().log(A))
                .i32(1).call(GET_TEXT_BACKGROUND).ifEqI64(0x40000000L, new P().log(Y))
                .i32(1).call(GET_TEXT_OPACITY).ifEqI64(255, new P().log(2));

        assertEquals("AYC", BillboardRun.run(main).assertFinished().trace());
    }

    @Test
    void onlyDisplaysInterpolateOnTheClient() {
        // This classification is what decides metadata-duration vs host tween in the renderer.
        assertEquals(true, EntityKind.BLOCK_DISPLAY.interpolatesOnClient());
        assertEquals(true, EntityKind.ITEM_DISPLAY.interpolatesOnClient());
        assertEquals(true, EntityKind.TEXT_DISPLAY.interpolatesOnClient());
        assertEquals(false, EntityKind.ARMOR_STAND.interpolatesOnClient());
        assertEquals(false, EntityKind.ITEM.interpolatesOnClient());
        // And which kinds share the display transform + billboard slot.
        assertEquals(true, EntityKind.TEXT_DISPLAY.isDisplay());
        assertEquals(false, EntityKind.ITEM.isDisplay());
        // Messages read naturally for both articles.
        assertEquals("an armor stand", EntityKind.ARMOR_STAND.labelWithArticle());
        assertEquals("a block display", EntityKind.BLOCK_DISPLAY.labelWithArticle());
        assertEquals("item entities", EntityKind.ITEM.plural());
    }

    // --- wrong-kind kills ---

    @Test
    void setTextOnABlockDisplayKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_BLOCK_DISPLAY).drop()
                .i32(1).i32(0).i32(1).call(SET_TEXT);
        BillboardRun.run(main).assertKilled("set_text", "is a block display", "text displays");
    }

    @Test
    void setBlockOnATextDisplayKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()
                .i32(1).i32(0).i32(1).call(SET_BLOCK);
        BillboardRun.run(main).assertKilled("set_block", "is a text display", "block displays");
    }

    @Test
    void setItemOnAnArmorStandKills() {
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(0).i32(1).call(SET_ITEM);
        BillboardRun.run(main).assertKilled("set_item", "is an armor stand");
    }

    @Test
    void poseOnAnItemDisplayKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop()
                .i32(1).i32(0).xyz(0, 0, 0).i64(0).call(SET_POSE);
        BillboardRun.run(main).assertKilled("set_pose", "is an item display", "armor stands");
    }

    @Test
    void yawOnADisplayKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_BLOCK_DISPLAY).drop()
                .i32(1).f64(90).i64(0).call(SET_YAW);
        BillboardRun.run(main).assertKilled("set_yaw", "is a block display", "armor stands");
    }

    @Test
    void rotationOnAnArmorStandKills() {
        // Armor stands turn with set_yaw and euler poses; there is no quaternion on them.
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).f64(0).f64(0).f64(0).f64(1).i64(0).call(SET_ROTATION);
        BillboardRun.run(main).assertKilled("set_rotation", "is an armor stand", "display entities");
    }

    @Test
    void scaleOnAnItemEntityKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM).drop()
                .i32(1).xyz(2, 2, 2).i64(0).call(SET_SCALE);
        BillboardRun.run(main).assertKilled("set_scale", "is an item entity", "display entities");
    }

    @Test
    void displayContextOnATextDisplayKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()
                .i32(1).i32(1).call(SET_DISPLAY_CONTEXT);
        BillboardRun.run(main).assertKilled("set_display_context", "is a text display");
    }

    @Test
    void billboardModeOnAnArmorStandKills() {
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(1).call(SET_BILLBOARD_MODE);
        BillboardRun.run(main).assertKilled("set_billboard_mode", "is an armor stand");
    }

    @Test
    void getterOnTheWrongKindKillsToo() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_BLOCK_DISPLAY).drop()
                .i32(1).call(GET_DISPLAY_CONTEXT).drop();
        BillboardRun.run(main).assertKilled("get_display_context", "is a block display");
    }

    // --- argument ranges ---

    @Test
    void poseParkOutOfRangeKills() {
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(6).xyz(0, 0, 0).i64(0).call(SET_POSE);
        BillboardRun.run(main).assertKilled("set_pose", "pose part 6 out of range 0..5");
    }

    @Test
    void equipmentSlotOutOfRangeKills() {
        P main = new P()
                .xyz(0, 0, 0).call(SPAWN_ARMOR_STAND).drop()
                .i32(1).i32(6).i32(0).i32(1).call(SET_EQUIPMENT);
        BillboardRun.run(main).assertKilled("set_equipment", "slot 6 out of range 0..5");
    }

    @Test
    void billboardModeOutOfRangeKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_BLOCK_DISPLAY).drop()
                .i32(1).i32(4).call(SET_BILLBOARD_MODE);
        BillboardRun.run(main).assertKilled("set_billboard_mode", "billboard mode 4 out of range 0..3");
    }

    @Test
    void textOpacityOutOfRangeKills() {
        P main = new P()
                .i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop()
                .i32(1).i64(256).call(SET_TEXT_OPACITY);
        BillboardRun.run(main).assertKilled("set_text_opacity", "opacity 256 out of range 0..255");
    }

    @Test
    void invalidItemKills() {
        ContentValidator rejectItems = new ContentValidator() {

            @Override
            public boolean isValidItem(String item) {
                return false;
            }

            @Override
            public boolean isValidText(String miniMessage) {
                return true;
            }
        };
        P main = new P().i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_ITEM_DISPLAY).drop();
        BillboardRun.validated(main, rejectItems).assertKilled("invalid item \"A\"");
    }

    @Test
    void invalidTextKills() {
        ContentValidator rejectText = new ContentValidator() {

            @Override
            public boolean isValidItem(String item) {
                return true;
            }

            @Override
            public boolean isValidText(String miniMessage) {
                return false;
            }
        };
        P main = new P().i32(0).i32(1).xyz(0, 0, 0).call(SPAWN_TEXT_DISPLAY).drop();
        BillboardRun.validated(main, rejectText).assertKilled("invalid MiniMessage text \"A\"");
    }
}
