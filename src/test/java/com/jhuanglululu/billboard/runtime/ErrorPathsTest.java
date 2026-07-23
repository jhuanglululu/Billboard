package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Error paths: fail(), missing ABI export, and instruction-budget exhaustion. */
class ErrorPathsTest {

    private static AnimationInstance instance(byte[] moduleBytes, long cap) {
        return new AnimationInstance("err", Module.parse(moduleBytes),
                new RecordingRenderer(), blockState -> true, (name, message) -> { }, cap);
    }

    private static TickResult drive(AnimationInstance inst, long budget) {
        TickResult result = null;
        for (long t = 0; t < 100; t++) {
            result = inst.tick(t, budget);
            if (!(result instanceof TickResult.Running)) {
                break;
            }
        }
        return result;
    }

    private static void assertErroredContains(TickResult r, String needle) {
        assertInstanceOf(TickResult.Errored.class, r);
        String msg = ((TickResult.Errored) r).message().toLowerCase(Locale.ROOT);
        assertTrue(msg.contains(needle.toLowerCase(Locale.ROOT)),
                "expected message to contain \"" + needle + "\" but was: "
                        + ((TickResult.Errored) r).message());
    }

    @Test
    void failRoutesToErroredWithMessage() {
        AnimationInstance inst = instance(RuntimeWasm.failModule(), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertErroredContains(r, "boom");
    }

    @Test
    void missingAbiExportIsErrored() {
        AnimationInstance inst = instance(RuntimeWasm.missingAbiModule(), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertErroredContains(r, "abi");
    }

    @Test
    void instructionBudgetExhaustionIsErrored() {
        AnimationInstance inst = instance(RuntimeWasm.infiniteLoopModule(), 1 << 20);
        // A tiny budget cannot let the infinite loop reach any blocking point.
        TickResult r = inst.tick(0, 1000);
        assertErroredContains(r, "budget");
    }
}
