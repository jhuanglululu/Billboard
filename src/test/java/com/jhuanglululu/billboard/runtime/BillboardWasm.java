package com.jhuanglululu.billboard.runtime;

import com.jhuanglululu.wasmachine.runtime.SyncWasm;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.Surface;

/**
 * Billboard's half of the {@link SyncWasm} fixture: the {@code "billboard"} import surface
 * and the {@code _billboard_abi} handshake, appended to the engine's own imports. The engine
 * fixture stays vocabulary-free — this class is where Billboard's entity and effect names
 * live, beside the host that implements them. Drift is self-detecting: a name missing here
 * fails instantiation with "missing host import".
 */
final class BillboardWasm {

    private BillboardWasm() {}

    /** The import module {@link AnimationInstance} registers. */
    static final String MODULE = "billboard";

    /** The handshake export Billboard's load check reads. */
    static final String ABI_EXPORT = "_billboard_abi";

    private static final Surface SURFACE = new Surface(MODULE, ABI_EXPORT);

    // Function-type handles. Engine-shaped signatures are re-registered locally on purpose:
    // the surface owns its whole type story, and the wasm type section tolerates duplicates.
    private static final int T_PTR_LEN = SURFACE.type(0x60, 0x02, 0x7F, 0x7F, 0x00);
    private static final int T_ID = SURFACE.type(0x60, 0x01, 0x7F, 0x00);
    private static final int T_ID_TO_I32 = SURFACE.type(0x60, 0x01, 0x7F, 0x01, 0x7F);
    private static final int T_ID_PTR_LEN = SURFACE.type(0x60, 0x03, 0x7F, 0x7F, 0x7F, 0x00);
    // (ptr, len, x, y, z) -> id — the string+position spawners
    private static final int T_SPAWN_STR =
            SURFACE.type(0x60, 0x05, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x01, 0x7F);
    // (x, y, z) -> id — spawn_armor_stand
    private static final int T_SPAWN_POS = SURFACE.type(0x60, 0x03, 0x7C, 0x7C, 0x7C, 0x01, 0x7F);
    private static final int T_ID_I64 = SURFACE.type(0x60, 0x02, 0x7F, 0x7E, 0x00);
    private static final int T_ID_TO_I64 = SURFACE.type(0x60, 0x01, 0x7F, 0x01, 0x7E);
    // (id, part, x, y, z, ticks) -> () — set_pose
    private static final int T_SET_POSE =
            SURFACE.type(0x60, 0x06, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x7E, 0x00);
    private static final int T_EQUIPMENT =
            SURFACE.type(0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x00);
    // (id, yaw, ticks) -> () — set_yaw
    private static final int T_SET_YAW = SURFACE.type(0x60, 0x03, 0x7F, 0x7C, 0x7E, 0x00);
    private static final int T_GET_YAW = SURFACE.type(0x60, 0x01, 0x7F, 0x01, 0x7C);
    // play_sound (ptr, len: i32, x, y, z: f64, category: i32, volume, pitch: f64)
    private static final int T_SOUND =
            SURFACE.type(0x60, 0x08, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x7F, 0x7C, 0x7C, 0x00);
    // emit_particle / _block / _item (ptr, len, x, y, z, count, ox, oy, oz, speed)
    private static final int T_PARTICLE = SURFACE.type(0x60, 0x0A, 0x7F, 0x7F, 0x7C, 0x7C,
            0x7C, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x00);
    // emit_particle_dust (r, g, b, size, x, y, z: f64, count: i32, ox, oy, oz, speed: f64)
    private static final int T_DUST = SURFACE.type(0x60, 0x0C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C,
            0x7C, 0x7C, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x00);
    // emit_particle_dust_transition (6 colours + size + x, y, z, count, ox, oy, oz, speed)
    private static final int T_DUST_TRANSITION = SURFACE.type(0x60, 0x0F, 0x7C, 0x7C, 0x7C,
            0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x00);
    // (id, x, y, z, ticks) -> () — set_position, set_scale
    private static final int T_SET_VEC =
            SURFACE.type(0x60, 0x05, 0x7F, 0x7C, 0x7C, 0x7C, 0x7E, 0x00);
    // (id, x, y, z, w, ticks) -> () — set_rotation
    private static final int T_SET_QUAT =
            SURFACE.type(0x60, 0x06, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x7E, 0x00);

    // Import indices, in declaration order (what `call` takes).
    // ABI v2 entities, then the v1 entity imports the kind-dispatch tests need.
    static final int SPAWN_ITEM_DISPLAY = SURFACE.imp("spawn_item_display", T_SPAWN_STR);
    static final int SPAWN_TEXT_DISPLAY = SURFACE.imp("spawn_text_display", T_SPAWN_STR);
    static final int SPAWN_ARMOR_STAND = SURFACE.imp("spawn_armor_stand", T_SPAWN_POS);
    static final int SPAWN_ITEM = SURFACE.imp("spawn_item", T_SPAWN_STR);
    static final int SET_ITEM = SURFACE.imp("set_item", T_ID_PTR_LEN);
    static final int GET_ITEM_LEN = SURFACE.imp("get_item_len", T_ID_TO_I32);
    static final int GET_ITEM = SURFACE.imp("get_item", T_PTR_LEN);
    static final int SET_DISPLAY_CONTEXT = SURFACE.imp("set_display_context", T_PTR_LEN);
    static final int GET_DISPLAY_CONTEXT = SURFACE.imp("get_display_context", T_ID_TO_I32);
    static final int SET_BILLBOARD_MODE = SURFACE.imp("set_billboard_mode", T_PTR_LEN);
    static final int GET_BILLBOARD_MODE = SURFACE.imp("get_billboard_mode", T_ID_TO_I32);
    static final int SET_TEXT = SURFACE.imp("set_text", T_ID_PTR_LEN);
    static final int GET_TEXT_LEN = SURFACE.imp("get_text_len", T_ID_TO_I32);
    static final int GET_TEXT = SURFACE.imp("get_text", T_PTR_LEN);
    static final int SET_TEXT_BACKGROUND = SURFACE.imp("set_text_background", T_ID_I64);
    static final int GET_TEXT_BACKGROUND = SURFACE.imp("get_text_background", T_ID_TO_I64);
    static final int SET_TEXT_OPACITY = SURFACE.imp("set_text_opacity", T_ID_I64);
    static final int GET_TEXT_OPACITY = SURFACE.imp("get_text_opacity", T_ID_TO_I64);
    static final int SET_LINE_WIDTH = SURFACE.imp("set_line_width", T_ID_I64);
    static final int GET_LINE_WIDTH = SURFACE.imp("get_line_width", T_ID_TO_I64);
    static final int SET_TEXT_FLAGS = SURFACE.imp("set_text_flags", T_PTR_LEN);
    static final int GET_TEXT_FLAGS = SURFACE.imp("get_text_flags", T_ID_TO_I32);
    static final int SET_POSE = SURFACE.imp("set_pose", T_SET_POSE);
    static final int GET_POSE = SURFACE.imp("get_pose", T_ID_PTR_LEN);
    static final int SET_EQUIPMENT = SURFACE.imp("set_equipment", T_EQUIPMENT);
    static final int SET_STAND_FLAGS = SURFACE.imp("set_stand_flags", T_PTR_LEN);
    static final int GET_STAND_FLAGS = SURFACE.imp("get_stand_flags", T_ID_TO_I32);
    static final int SET_YAW = SURFACE.imp("set_yaw", T_SET_YAW);
    static final int GET_YAW = SURFACE.imp("get_yaw", T_GET_YAW);
    static final int PLAY_SOUND = SURFACE.imp("play_sound", T_SOUND);
    static final int EMIT_PARTICLE = SURFACE.imp("emit_particle", T_PARTICLE);
    static final int EMIT_PARTICLE_DUST = SURFACE.imp("emit_particle_dust", T_DUST);
    static final int EMIT_PARTICLE_DUST_TRANSITION =
            SURFACE.imp("emit_particle_dust_transition", T_DUST_TRANSITION);
    static final int EMIT_PARTICLE_BLOCK = SURFACE.imp("emit_particle_block", T_PARTICLE);
    static final int EMIT_PARTICLE_ITEM = SURFACE.imp("emit_particle_item", T_PARTICLE);
    static final int SPAWN_BLOCK_DISPLAY = SURFACE.imp("spawn_block_display", T_SPAWN_STR);
    static final int SET_BLOCK = SURFACE.imp("set_block", T_ID_PTR_LEN);
    static final int SET_POSITION = SURFACE.imp("set_position", T_SET_VEC);
    static final int SET_ROTATION = SURFACE.imp("set_rotation", T_SET_QUAT);
    static final int SET_SCALE = SURFACE.imp("set_scale", T_SET_VEC);
    static final int DESPAWN = SURFACE.imp("despawn", T_ID);
    static final int GET_POSITION = SURFACE.imp("get_position", T_PTR_LEN);
    static final int GET_BLOCK_LEN = SURFACE.imp("get_block_len", T_ID_TO_I32);
    static final int GET_BLOCK = SURFACE.imp("get_block", T_PTR_LEN);

    /** Wraps {@code main} into a module reporting both current ABI versions. */
    static byte[] module(P main) {
        return SyncWasm.module(main, SURFACE, AnimationInstance.ABI_VERSION);
    }

    /** Wraps {@code main} into a module reporting each handshake version explicitly. */
    static byte[] module(P main, int engineAbiVersion, int billboardAbiVersion) {
        return SyncWasm.module(main, engineAbiVersion, SURFACE, billboardAbiVersion);
    }
}
