package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The ABI 4 handshake pair. The namespace split (3) and the shared-memory/environ/player break (4)
 * were each one coordinated change, so there is no backwards compatibility left to keep: exactly
 * one Billboard version is accepted, and the engine's own version is checked beside it — both at
 * load time.
 */
class AbiVersionTest {

    private static AnimationInstance instance(int billboardAbi, int engineAbi) {
        return new AnimationInstance("abi",
                Module.parse(BillboardWasm.module(new P().log(0), engineAbi, billboardAbi)),
                new RecordingRenderer(), blockState -> true, (name, message) -> { }, 1 << 20);
    }

    private static TickResult firstTick(int billboardAbi) {
        return instance(billboardAbi, MachineInstance.ENGINE_ABI_VERSION).tick(0, 1_000_000);
    }

    @Test
    void theCurrentVersionIsAccepted() {
        assertInstanceOf(TickResult.Finished.class, firstTick(AnimationInstance.ABI_VERSION));
        assertInstanceOf(TickResult.Finished.class, firstTick(4));
    }

    @Test
    void olderVersionsAreRejected() {
        // A v1/v2 guest expects fork/sleep/realloc inside the "billboard" module, where this host
        // no longer provides them; a v3 guest asks the engine for `fork`, which engine ABI 2
        // deleted. None of the three could link even if the handshake waved them through.
        for (int old : new int[] {1, 2, 3}) {
            TickResult result = firstTick(old);
            assertInstanceOf(TickResult.Errored.class, result);
            assertTrue(((TickResult.Errored) result).message().contains("returned " + old),
                    ((TickResult.Errored) result).message());
        }
    }

    @Test
    void newerVersionIsRejected() {
        TickResult result = firstTick(5);
        assertInstanceOf(TickResult.Errored.class, result);
        String message = ((TickResult.Errored) result).message();
        assertTrue(message.contains("_billboard_abi") && message.contains("returned 5")
                        && message.contains("4..4"),
                "expected the handshake message to name the export and versions but was: " + message);
    }

    @Test
    void theEngineHandshakeIsCheckedToo() {
        // Billboard layers its check on the engine's, so a guest built against a different engine
        // ABI is refused with the engine's export named — before Billboard's own check matters.
        AnimationInstance instance = instance(AnimationInstance.ABI_VERSION,
                MachineInstance.ENGINE_ABI_VERSION + 1);
        String message = instance.loadError().orElseThrow();
        assertTrue(message.contains("_engine_abi"), message);
    }
}
