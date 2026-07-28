package com.jhuanglululu.billboard.runtime;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncRun;
import com.jhuanglululu.wasmachine.runtime.SyncWasm;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives a {@link SyncWasm} module through a real {@link AnimationInstance} — the engine plus
 * Billboard's own imports — and collects its log trace. The outcome assertions come from the
 * engine's {@link SyncRun} fixture, so an engine-only run and a Billboard run are judged by
 * exactly the same rules; only the instance under them differs.
 */
final class BillboardRun {

    private BillboardRun() {}

    /** Runs with a 1 MiB cap, instance seed 0, 40 ticks and a generous fuel budget. */
    static SyncRun.Result run(SyncWasm.P main) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L, new RecordingRenderer(),
                ContentValidator.PERMISSIVE);
    }

    /** Runs with a caller-supplied renderer, so a test can assert on what was rendered. */
    static SyncRun.Result rendered(SyncWasm.P main, RecordingRenderer renderer) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L, renderer, ContentValidator.PERMISSIVE);
    }

    /** Runs with a caller-supplied content validator, for the invalid item/text kill paths. */
    static SyncRun.Result validated(SyncWasm.P main, ContentValidator content) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L, new RecordingRenderer(), content);
    }

    static SyncRun.Result run(SyncWasm.P main, long memoryCap, long seed, int maxTicks, long budget,
            RecordingRenderer renderer, ContentValidator content) {
        List<String> logs = new ArrayList<>();
        AnimationInstance instance = new AnimationInstance("sync",
                Module.parse(SyncWasm.module(main)), renderer, blockState -> true, content,
                (name, message) -> logs.add(message), memoryCap, seed);
        return SyncRun.drive((tick, fuel) -> outcomeOf(instance.tick(tick, fuel)),
                logs, maxTicks, budget);
    }

    /** Billboard's own {@link TickResult}, reduced to the outcome the shared assertions read. */
    private static SyncRun.Outcome outcomeOf(TickResult result) {
        return switch (result) {
            case TickResult.Running ignored -> null;
            case TickResult.Finished ignored -> SyncRun.Outcome.ofFinished();
            case TickResult.Errored e -> SyncRun.Outcome.ofErrored(e.message());
        };
    }
}
