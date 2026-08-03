package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.jhuanglululu.wasm.ExecResult;
import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.HostFunction;
import com.jhuanglululu.wasm.Import;
import com.jhuanglululu.wasm.Instance;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test on the real {@code rustc} artifact: instantiate with stub host imports
 * (each returns 0, none suspends) and call both handshakes, asserting the ABI 4 pair —
 * {@code _engine_abi() = 2} and {@code _billboard_abi() = 4}. {@code _engine_main} is
 * deliberately not called here — it needs real host semantics, which
 * {@code billboard.runtime.DemoIntegrationTest} supplies.
 */
class InterpreterDemoSmokeTest {

    private static byte[] loadDemo() throws IOException {
        try (InputStream in = InterpreterDemoSmokeTest.class.getResourceAsStream("/demo.wasm")) {
            assertNotNull(in, "demo.wasm fixture is missing");
            return in.readAllBytes();
        }
    }

    /** Invokes a no-argument {@code i32} export on a fully stubbed instance. */
    private static int handshake(String export) throws IOException {
        Module module = Module.parse(loadDemo());

        // Stub every import, whichever module it belongs to, with a no-op returning 0.
        Map<String, HostFunction> imports = new HashMap<>();
        for (Import imp : module.imports()) {
            imports.put(imp.module() + "." + imp.name(), (ctx, args) -> 0L);
        }

        Instance inst = new Instance(module, imports);
        ExecutionContext ctx = inst.instantiate();

        ExecResult r = inst.invoke(ctx, export, new long[0], 1_000_000);
        assertInstanceOf(ExecResult.Completed.class, r, () -> "expected completion but was " + r);
        return (int) ((ExecResult.Completed) r).values()[0];
    }

    @Test
    void billboardAbiReturnsFour() throws IOException {
        assertEquals(AnimationInstance.ABI_VERSION, handshake("_billboard_abi"));
        assertEquals(4, handshake("_billboard_abi"));
    }

    @Test
    void engineAbiReturnsTwo() throws IOException {
        assertEquals(MachineInstance.ENGINE_ABI_VERSION, handshake("_engine_abi"));
        assertEquals(2, handshake("_engine_abi"));
    }
}
