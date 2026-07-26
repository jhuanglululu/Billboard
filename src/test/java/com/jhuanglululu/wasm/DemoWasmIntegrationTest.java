package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Integration test against a real {@code rustc}-emitted artifact: the v2 Billboard demo
 * animation compiled for {@code wasm32-unknown-unknown}. Asserts the import surface (the
 * ABI v2 host functions the SDK binds) and the export <em>names</em>; export signatures
 * are deliberately not asserted here — the runtime's own handshake covers those.
 */
class DemoWasmIntegrationTest {

    private static byte[] loadDemo() throws IOException {
        try (InputStream in = DemoWasmIntegrationTest.class.getResourceAsStream("/demo.wasm")) {
            assertNotNull(in, "demo.wasm fixture is missing from test resources");
            return in.readAllBytes();
        }
    }

    @Test
    void demoParses() throws IOException {
        Module m = Module.parse(loadDemo());
        assertTrue(m.functionCount() > 0);
        assertEquals(1, m.memories().size());
        assertEquals(1, m.tables().size());
    }

    @Test
    void everyImportIsABillboardHostFunction() throws IOException {
        Module m = Module.parse(loadDemo());

        Set<String> names = new TreeSet<>();
        for (Import imp : m.imports()) {
            assertEquals("billboard", imp.module(),
                    "unexpected import module: " + imp.module() + "." + imp.name());
            assertEquals(ExternalKind.FUNCTION, imp.kind());
            names.add(imp.name());
        }
        assertEquals(names.size(), m.imports().size(), "no import is bound twice");

        // Every v1 import is still there: v2 is purely additive.
        for (String v1 : new String[] {
                "despawn", "exit", "fail", "fork", "get_block", "get_block_len", "get_position",
                "get_rotation", "get_scale", "is_alive", "join", "log", "realloc", "set_block",
                "set_position", "set_rotation", "set_scale", "sleep", "spawn_block_display"}) {
            assertTrue(names.contains(v1), "v1 import \"" + v1 + "\" disappeared");
        }
        // And the v2 surface the demo exercises: the four new spawns, their attributes, effects,
        // sync and random.
        for (String v2 : new String[] {
                "spawn_item_display", "spawn_text_display", "spawn_armor_stand", "spawn_item",
                "set_item", "get_item", "set_display_context", "set_billboard_mode", "set_text",
                "get_text", "set_text_background", "set_text_opacity", "set_line_width",
                "set_text_flags", "set_pose", "get_pose", "set_equipment", "set_stand_flags",
                "set_yaw", "get_yaw", "play_sound", "emit_particle", "emit_particle_dust",
                "emit_particle_dust_transition", "emit_particle_block", "emit_particle_item",
                "signal_new", "signal_notify", "barrier_new", "wait_all", "wait_any", "wait",
                "channel_new", "channel_send", "channel_recv_len", "channel_recv",
                "channel_try_len", "channel_peek", "random_det", "seed_random"}) {
            assertTrue(names.contains(v2), "v2 import \"" + v2 + "\" missing");
        }
        // The documented trap: random_nondet is linked because default_random() picks its stream at
        // run time, so both arms compile in. It is imported but never called — the runtime's
        // DemoIntegrationTest asserts the call count is zero.
        assertTrue(names.contains("random_nondet"),
                "random_nondet is expected in the import list even though it is never called");
    }

    @Test
    void exportsContainTheExpectedNames() throws IOException {
        Module m = Module.parse(loadDemo());

        Set<String> names = new TreeSet<>();
        for (Export e : m.exports()) {
            names.add(e.name());
        }

        // Names only — signatures deliberately not asserted (fixture refresh incoming).
        for (String required : new String[] {
                "memory", "_billboard_main", "_billboard_abi", "__heap_base", "__data_end"}) {
            assertTrue(names.contains(required),
                    "expected export \"" + required + "\"; exports were " + names);
        }
    }
}
