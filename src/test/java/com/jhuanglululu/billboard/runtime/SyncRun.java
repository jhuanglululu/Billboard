package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Drives a {@link SyncWasm} module through {@link AnimationInstance} and collects its log trace. */
final class SyncRun {

    private SyncRun() {}

    /** Runs with a 1 MiB cap, instance seed 0, 40 ticks and a generous fuel budget. */
    static Result run(SyncWasm.P main) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L);
    }

    /** Runs with instance seed {@code seed} (which selects the {@code notify_one(Random)} draw). */
    static Result seeded(SyncWasm.P main, long seed) {
        return run(main, 1 << 20, seed, 40, 10_000_000L);
    }

    static Result run(SyncWasm.P main, long memoryCap, long seed, int maxTicks, long budget) {
        return run(main, memoryCap, seed, maxTicks, budget, new RecordingRenderer(),
                ContentValidator.PERMISSIVE);
    }

    /** Runs with a caller-supplied renderer, so a test can assert on what was rendered. */
    static Result rendered(SyncWasm.P main, RecordingRenderer renderer) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L, renderer, ContentValidator.PERMISSIVE);
    }

    /** Runs with a caller-supplied content validator, for the invalid item/text kill paths. */
    static Result validated(SyncWasm.P main, ContentValidator content) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L, new RecordingRenderer(), content);
    }

    static Result run(SyncWasm.P main, long memoryCap, long seed, int maxTicks, long budget,
            RecordingRenderer renderer, ContentValidator content) {
        List<String> logs = new ArrayList<>();
        AnimationInstance instance = new AnimationInstance("sync",
                Module.parse(SyncWasm.module(main)), renderer, blockState -> true, content,
                (name, message) -> logs.add(message), memoryCap, seed);
        TickResult result = null;
        for (long tick = 0; tick < maxTicks; tick++) {
            result = instance.tick(tick, budget);
            if (!(result instanceof TickResult.Running)) {
                break;
            }
        }
        return new Result(result, logs);
    }

    record Result(TickResult result, List<String> logs) {

        /** The logged characters concatenated, e.g. {@code "ABC"} — the observable ordering. */
        String trace() {
            return String.join("", logs);
        }

        Result assertFinished() {
            assertInstanceOf(TickResult.Finished.class, result,
                    "expected the animation to finish but got " + result + " (trace " + trace() + ")");
            return this;
        }

        /** Asserts the animation was killed with a message containing every given substring. */
        void assertKilled(String... needles) {
            assertInstanceOf(TickResult.Errored.class, result,
                    "expected the animation to be killed but got " + result);
            String message = ((TickResult.Errored) result).message();
            for (String needle : needles) {
                assertTrue(message.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)),
                        "expected the kill message to contain \"" + needle + "\" but was: " + message);
            }
        }
    }
}
