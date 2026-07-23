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
 * Integration test against a real {@code rustc}-emitted artifact: the Billboard demo
 * animation compiled for {@code wasm32-unknown-unknown}. Asserts the import surface
 * (the 19 host functions the SDK binds) and the export <em>names</em> only — export
 * signatures are intentionally not asserted, because the SDK is about to add a return
 * value to {@code _billboard_main} and this fixture will be refreshed.
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
    void importsAreExactlyThe19BillboardFunctions() throws IOException {
        Module m = Module.parse(loadDemo());

        Set<String> billboardFuncImports = new TreeSet<>();
        for (Import imp : m.imports()) {
            assertEquals("billboard", imp.module(),
                    "unexpected import module: " + imp.module() + "." + imp.name());
            assertEquals(ExternalKind.FUNCTION, imp.kind());
            billboardFuncImports.add(imp.name());
        }

        Set<String> expected = new TreeSet<>(Set.of(
                "despawn", "exit", "fail", "fork", "get_block", "get_block_len",
                "get_position", "get_rotation", "get_scale", "is_alive", "join", "log",
                "realloc", "set_block", "set_position", "set_rotation", "set_scale",
                "sleep", "spawn_block_display"));

        assertEquals(expected, billboardFuncImports);
        assertEquals(19, m.imports().size());
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
