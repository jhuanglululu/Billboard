package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test on the real {@code rustc} artifact: instantiate with stub
 * host imports (each returns 0, none suspends) and call {@code _billboard_abi()},
 * asserting the ABI handshake returns 1. {@code _billboard_main} is deliberately not
 * called — it needs real host semantics, which come in a later step.
 */
class InterpreterDemoSmokeTest {

    private static byte[] loadDemo() throws IOException {
        try (InputStream in = InterpreterDemoSmokeTest.class.getResourceAsStream("/demo.wasm")) {
            assertNotNull(in, "demo.wasm fixture is missing");
            return in.readAllBytes();
        }
    }

    @Test
    void billboardAbiReturnsOne() throws IOException {
        Module module = Module.parse(loadDemo());

        // Stub every billboard import with a no-op returning 0.
        Map<String, HostFunction> imports = new HashMap<>();
        for (Import imp : module.imports()) {
            imports.put(imp.module() + "." + imp.name(), (ctx, args) -> 0L);
        }

        Instance inst = new Instance(module, imports);
        ExecutionContext ctx = inst.instantiate();

        ExecResult r = inst.invoke(ctx, "_billboard_abi", new long[0], 1_000_000);
        assertInstanceOf(ExecResult.Completed.class, r, () -> "expected completion but was " + r);
        assertEquals(1, (int) ((ExecResult.Completed) r).values()[0]);
    }
}
