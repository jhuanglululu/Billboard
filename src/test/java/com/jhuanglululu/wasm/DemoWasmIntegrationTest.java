package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Integration test against a real {@code rustc}-emitted artifact: the Billboard demo animation
 * compiled for {@code wasm32-unknown-unknown}. Asserts the ABI 3 import surface — which module
 * each host function is asked of, the boundary the namespace split exists to make structural —
 * and the export <em>names</em>; export signatures are deliberately not asserted here, since
 * the runtime's own handshake covers those.
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

    /** The imported names, grouped by the module they are asked of. */
    private static Map<String, Set<String>> importsByModule() throws IOException {
        Module m = Module.parse(loadDemo());
        Map<String, Set<String>> byModule = new TreeMap<>();
        for (Import imp : m.imports()) {
            assertEquals(ExternalKind.FUNCTION, imp.kind());
            byModule.computeIfAbsent(imp.module(), key -> new TreeSet<>()).add(imp.name());
        }
        int total = byModule.values().stream().mapToInt(Set::size).sum();
        assertEquals(total, m.imports().size(), "no import is bound twice");
        return byModule;
    }

    @Test
    void everyImportComesFromEitherTheEngineOrTheBillboardModule() throws IOException {
        // The whole point of ABI 3: the boundary is structural, so this assertion is the one
        // that would catch an engine function drifting back into the plugin's namespace.
        assertEquals(Set.of("engine", "billboard"), importsByModule().keySet());
    }

    @Test
    void theEngineModuleSuppliesTasksSyncRandomAndMath() throws IOException {
        Set<String> engine = importsByModule().get("engine");

        for (String name : new String[] {
                // tasks and memory
                "realloc", "fork", "join", "exit", "sleep", "log", "fail",
                // sync
                "signal_new", "signal_notify", "barrier_new", "wait_all", "wait_any", "wait",
                "channel_new", "channel_send", "channel_recv_len", "channel_recv",
                "channel_try_len", "channel_peek",
                // random
                "random_det", "seed_random",
                // the math kernel the demo routes its transcendentals through
                "cbrt", "pow", "sin", "cos"}) {
            assertTrue(engine.contains(name),
                    "engine import \"" + name + "\" missing; engine module was " + engine);
        }
        // The documented trap: random_nondet is linked because default_random() picks its stream at
        // run time, so both arms compile in. It is imported but never called — the runtime's
        // DemoIntegrationTest asserts the call count is zero.
        assertTrue(engine.contains("random_nondet"),
                "random_nondet is expected in the import list even though it is never called");
    }

    @Test
    void theBillboardModuleSuppliesOnlyEntitiesAndEffects() throws IOException {
        Set<String> billboard = importsByModule().get("billboard");

        for (String name : new String[] {
                "spawn_block_display", "spawn_item_display", "spawn_text_display",
                "spawn_armor_stand", "spawn_item", "despawn", "is_alive",
                "set_block", "get_block", "get_block_len", "set_position", "get_position",
                "set_rotation", "get_rotation", "set_scale", "get_scale",
                "set_item", "get_item", "set_display_context", "set_billboard_mode",
                "set_text", "get_text", "set_text_background", "set_text_opacity",
                "set_line_width", "set_text_flags", "set_pose", "get_pose", "set_equipment",
                "set_stand_flags", "set_yaw", "get_yaw", "play_sound", "emit_particle",
                "emit_particle_dust", "emit_particle_dust_transition", "emit_particle_block",
                "emit_particle_item"}) {
            assertTrue(billboard.contains(name),
                    "plugin import \"" + name + "\" missing; billboard module was " + billboard);
        }
        // And nothing engine-owned leaked in here — the failure this test exists for.
        for (String engineOwned : new String[] {
                "fork", "sleep", "exit", "realloc", "log", "fail", "wait", "channel_new",
                "random_det", "pow"}) {
            assertFalse(billboard.contains(engineOwned),
                    "engine function \"" + engineOwned + "\" must not be in the plugin module");
        }
    }

    @Test
    void exportsAreTheAbiThreeSet() throws IOException {
        Module m = Module.parse(loadDemo());

        Set<String> names = new TreeSet<>();
        for (Export e : m.exports()) {
            names.add(e.name());
        }

        // Both handshakes, the engine-named entry, and the allocator bounds. _billboard_main is
        // gone: the entry point is the engine's since ABI 3.
        assertEquals(Set.of("memory", "_engine_main", "_engine_abi", "_billboard_abi",
                "__heap_base", "__data_end"), names);
    }
}
