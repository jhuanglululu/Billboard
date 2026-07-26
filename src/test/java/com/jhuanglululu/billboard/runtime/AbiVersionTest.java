package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.runtime.SyncWasm.P;
import com.jhuanglululu.wasm.Module;
import org.junit.jupiter.api.Test;

/** ABI v2 is purely additive, so the host accepts both 1 and 2 — and nothing else. */
class AbiVersionTest {

    private static TickResult firstTick(int abiVersion) {
        AnimationInstance instance = new AnimationInstance("abi",
                Module.parse(SyncWasm.module(new P().log(0), abiVersion)), new RecordingRenderer(),
                blockState -> true, (name, message) -> { }, 1 << 20);
        return instance.tick(0, 1_000_000);
    }

    @Test
    void versionOneIsAccepted() {
        assertInstanceOf(TickResult.Finished.class, firstTick(1));
    }

    @Test
    void versionTwoIsAccepted() {
        assertInstanceOf(TickResult.Finished.class, firstTick(2));
    }

    @Test
    void newerVersionIsRejected() {
        TickResult result = firstTick(3);
        assertInstanceOf(TickResult.Errored.class, result);
        String message = ((TickResult.Errored) result).message();
        assertTrue(message.contains("returned 3") && message.contains("1..2"),
                "expected the handshake message to name both versions but was: " + message);
    }
}
