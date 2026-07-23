package com.jhuanglululu.billboard.runtime;

import com.jhuanglululu.wasm.ExecResult;
import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.Export;
import com.jhuanglululu.wasm.ExternalKind;
import com.jhuanglululu.wasm.HostFunction;
import com.jhuanglululu.wasm.Instance;
import com.jhuanglululu.wasm.Module;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One running animation: it owns the WASM {@link Instance}, the cooperative tasks that
 * run on it (each an {@link ExecutionContext} with its own memory + {@link HostAllocator},
 * a wake condition, and a spawn-order index), and the shared {@link EntityRegistry}. It
 * implements every {@code "billboard"} ABI import and drives execution one game tick at a
 * time via {@link #tick}.
 *
 * <p><b>Scheduling.</b> Each tick, every due task runs (to its next blocking point) in
 * <em>spawn order</em>, sharing one instruction budget. A task blocks at {@code sleep}
 * (wakes at {@code currentTick + ticks}) or {@code join} (wakes when its target ends).
 * A forked child becomes runnable immediately and runs the same tick, after the parent
 * yields. Task 0's {@code main} returning ends the whole animation; any other task
 * returning or calling {@code exit} just ends that task.
 *
 * <p><b>Design decisions.</b>
 * <ul>
 *   <li><b>{@code sleep(0)}</b> wakes on the <em>next</em> tick ({@code currentTick + 1}),
 *       not the same tick: a cooperative yield that always advances the clock, so a task
 *       can never busy-spin within a tick holding the shared budget. Positive sleeps are
 *       exact ({@code currentTick + ticks}).</li>
 *   <li><b>Shared budget.</b> The per-tick budget is spent across all tasks in the order
 *       they run; if any task cannot reach a blocking point before it is exhausted, the
 *       whole animation errors (this is the runaway-loop guard).</li>
 *   <li><b>Fork ids.</b> {@code fork} returns the child's task id in the parent and
 *       {@code 0} in the child (Linux semantics). The child is a deep copy of the parent
 *       taken at the fork suspension, resumed with {@code 0}.</li>
 * </ul>
 *
 * <p>Not thread-safe: an instance is confined to one worker at a time.
 */
public final class AnimationInstance {

    /** The ABI version this host speaks; {@code _billboard_abi} must return it. */
    public static final int ABI_VERSION = 1;

    private static final long[] NO_ARGS = new long[0];

    // Suspension request payloads a host import hands to the interpreter.
    private sealed interface Request permits SleepRequest, JoinRequest, ForkRequest, ExitRequest {}

    private record SleepRequest(long ticks) implements Request {}

    private record JoinRequest(int taskId) implements Request {}

    private record ForkRequest() implements Request {}

    private record ExitRequest() implements Request {}

    private static final ForkRequest FORK = new ForkRequest();
    private static final ExitRequest EXIT = new ExitRequest();

    private enum TaskState {
        /** Task 0 before its {@code main} has been invoked. */
        NOT_STARTED,
        /** A forked child awaiting its first resume (which returns {@code 0} from fork). */
        FORK_PENDING,
        /** Ready to run/continue now. */
        RUNNABLE,
        /** Parked until {@link Task#wakeTick}. */
        SLEEPING,
        /** Parked until {@link Task#joinTarget} finishes. */
        JOINING,
        /** Ended (returned, exited, or killed). */
        FINISHED
    }

    private static final class Task {
        final int id;
        ExecutionContext ctx;
        HostAllocator allocator;
        TaskState state;
        long wakeTick;
        int joinTarget = -1;

        Task(int id) {
            this.id = id;
        }
    }

    private final String name;
    private final Renderer renderer;
    private final BlockStateValidator validator;
    private final LogSink logSink;

    private final Instance wasm;
    private final EntityRegistry registry = new EntityRegistry();
    // In ascending spawn order: task 0 first, forked children appended as created.
    private final List<Task> tasks = new ArrayList<>();

    private int nextTaskId = 1;

    private Task currentTask;
    private long remainingFuel;
    private long tickBudget;

    // The two-call get_block protocol caches the string between the paired calls.
    private byte[] pendingBlock;

    private TickResult terminal; // set once the animation ends (Finished/Errored)

    /**
     * @param name           the animation name (for log routing)
     * @param module         the parsed animation module
     * @param renderer       receives visual side effects
     * @param validator      validates block-state strings
     * @param logSink        receives guest {@code log} output
     * @param memoryCapBytes per-instance heap cap enforced by the {@link HostAllocator}
     */
    public AnimationInstance(String name, Module module, Renderer renderer,
            BlockStateValidator validator, LogSink logSink, long memoryCapBytes) {
        this.name = name;
        this.renderer = renderer;
        this.validator = validator;
        this.logSink = logSink;
        this.wasm = new Instance(module, buildImports());

        ExecutionContext ctx0 = wasm.instantiate();
        int heapBase = exportedGlobalI32(module, ctx0, "__heap_base");

        Task task0 = new Task(0);
        task0.ctx = ctx0;
        task0.allocator = new HostAllocator(heapBase, memoryCapBytes);
        task0.state = TaskState.NOT_STARTED;
        task0.wakeTick = 0;
        tasks.add(task0);

        validateAbi(ctx0);
    }

    private void validateAbi(ExecutionContext ctx0) {
        try {
            currentTask = tasks.get(0);
            ExecResult r = wasm.invoke(ctx0, "_billboard_abi", NO_ARGS, 1_000_000);
            if (!(r instanceof ExecResult.Completed c) || c.values().length != 1
                    || (int) c.values()[0] != ABI_VERSION) {
                this.terminal = new TickResult.Errored(
                        "ABI handshake failed: _billboard_abi did not return " + ABI_VERSION);
            }
        } catch (RuntimeException e) {
            this.terminal = new TickResult.Errored(
                    "module does not export a valid _billboard_abi(): " + e.getMessage());
        }
    }

    private static int exportedGlobalI32(Module module, ExecutionContext ctx, String exportName) {
        for (Export e : module.exports()) {
            if (e.kind() == ExternalKind.GLOBAL && e.name().equals(exportName)) {
                return (int) ctx.readGlobal(e.index());
            }
        }
        throw new IllegalArgumentException("module has no exported global \"" + exportName + "\"");
    }

    /** The animation name. */
    public String name() {
        return name;
    }

    /** The shared entity registry (host-side source of truth). */
    public EntityRegistry registry() {
        return registry;
    }

    /**
     * Advances the animation by one game tick: resumes every due task in spawn order,
     * sharing {@code fuelBudget} instructions across them.
     *
     * @param currentTick the current game tick (monotonic)
     * @param fuelBudget  the instruction budget for this tick, across all tasks
     * @return {@link TickResult.Running}, {@link TickResult.Finished}, or {@link TickResult.Errored}
     */
    public TickResult tick(long currentTick, long fuelBudget) {
        if (terminal != null) {
            return terminal;
        }
        this.tickBudget = fuelBudget;
        this.remainingFuel = fuelBudget;
        try {
            List<Integer> ranThisRound = new ArrayList<>();
            boolean ranSinceClear = false;
            while (true) {
                Task cand = pickRunnable(currentTick, ranThisRound);
                if (cand == null) {
                    if (!ranSinceClear) {
                        break;
                    }
                    ranThisRound.clear();
                    ranSinceClear = false;
                    continue;
                }
                ranThisRound.add(cand.id);
                ranSinceClear = true;
                TickResult r = runTurn(cand, currentTick);
                if (r != null) {
                    this.terminal = r;
                    return r;
                }
            }
        } catch (AnimationAbort e) {
            this.terminal = new TickResult.Errored(e.getMessage());
            return this.terminal;
        }
        return new TickResult.Running();
    }

    private Task pickRunnable(long currentTick, List<Integer> ranThisRound) {
        for (Task t : tasks) { // tasks are in ascending spawn-index order
            if (t.state != TaskState.FINISHED && !ranThisRound.contains(t.id) && dueAt(t, currentTick)) {
                return t;
            }
        }
        return null;
    }

    private boolean dueAt(Task t, long currentTick) {
        return switch (t.state) {
            case NOT_STARTED, FORK_PENDING, RUNNABLE -> true;
            case SLEEPING -> t.wakeTick <= currentTick;
            case JOINING -> {
                Task target = byId(t.joinTarget);
                yield target == null || target.state == TaskState.FINISHED;
            }
            case FINISHED -> false;
        };
    }

    private TickResult runTurn(Task task, long currentTick) {
        ExecResult result = startOrResume(task);
        while (result instanceof ExecResult.Suspended s && s.request() instanceof ForkRequest) {
            Task child = doFork(task, currentTick);
            result = resumeWith(task, child.id);
        }
        return applyResult(task, result, currentTick);
    }

    private ExecResult startOrResume(Task task) {
        currentTask = task;
        ExecResult r;
        switch (task.state) {
            case NOT_STARTED -> {
                task.state = TaskState.RUNNABLE;
                r = wasm.invoke(task.ctx, "_billboard_main", NO_ARGS, remainingFuel);
            }
            case FORK_PENDING -> {
                task.state = TaskState.RUNNABLE;
                r = wasm.resume(task.ctx, remainingFuel, 0L);
            }
            default -> {
                task.state = TaskState.RUNNABLE;
                r = wasm.resume(task.ctx, remainingFuel);
            }
        }
        remainingFuel -= task.ctx.fuelConsumed();
        return r;
    }

    private ExecResult resumeWith(Task task, long hostResult) {
        currentTask = task;
        ExecResult r = wasm.resume(task.ctx, remainingFuel, hostResult);
        remainingFuel -= task.ctx.fuelConsumed();
        return r;
    }

    private Task doFork(Task parent, long currentTick) {
        Task child = new Task(nextTaskId++);
        child.ctx = parent.ctx.copy();
        child.allocator = parent.allocator.copy();
        child.state = TaskState.FORK_PENDING;
        child.wakeTick = currentTick;
        tasks.add(child);
        return child;
    }

    private TickResult applyResult(Task task, ExecResult result, long currentTick) {
        return switch (result) {
            case ExecResult.Completed c -> onCompleted(task, c);
            case ExecResult.Suspended s -> onSuspended(task, s, currentTick);
            case ExecResult.Trapped t ->
                    new TickResult.Errored("animation trapped: " + t.message());
            case ExecResult.FuelExhausted ignored -> new TickResult.Errored(
                    "instruction budget of " + tickBudget + " exhausted before a blocking point"
                            + " (runaway loop?) in task " + task.id);
        };
    }

    private TickResult onCompleted(Task task, ExecResult.Completed c) {
        if (task.id == 0) {
            int wire = (int) c.values()[0];
            return ExitCode.fromWire(wire)
                    .<TickResult>map(code -> {
                        finishAll();
                        return new TickResult.Finished(code);
                    })
                    .orElseGet(() -> new TickResult.Errored("main returned invalid exit code " + wire));
        }
        finishTask(task);
        return null;
    }

    private TickResult onSuspended(Task task, ExecResult.Suspended s, long currentTick) {
        if (!(s.request() instanceof Request req)) {
            return new TickResult.Errored("unexpected suspension request: " + s.request());
        }
        return switch (req) {
            case SleepRequest sr -> {
                long t = sr.ticks();
                task.wakeTick = currentTick + (t <= 0 ? 1 : t); // sleep(0) yields to the next tick
                task.state = TaskState.SLEEPING;
                yield null;
            }
            case JoinRequest jr -> {
                Task target = byId(jr.taskId());
                if (target == null) {
                    yield new TickResult.Errored("join on unknown task id " + jr.taskId());
                }
                if (target.state == TaskState.FINISHED) {
                    task.state = TaskState.RUNNABLE; // join returns; runs again this tick
                } else {
                    task.state = TaskState.JOINING;
                    task.joinTarget = jr.taskId();
                }
                yield null;
            }
            case ExitRequest ignored -> {
                if (task.id == 0) {
                    finishAll();
                    yield new TickResult.Finished(ExitCode.END);
                }
                finishTask(task);
                yield null;
            }
            // fork is fully handled in runTurn's inline loop before we get here.
            case ForkRequest ignored -> new TickResult.Errored("stray fork suspension");
        };
    }

    private void finishTask(Task task) {
        task.state = TaskState.FINISHED;
    }

    private void finishAll() {
        for (Task t : tasks) {
            t.state = TaskState.FINISHED;
        }
    }

    private Task byId(int id) {
        for (Task t : tasks) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    private void killTask(int id) {
        Task t = byId(id);
        if (t != null) {
            // Killed tasks' destructors never run; owned entities are orphaned until
            // end-of-animation cleanup.
            t.state = TaskState.FINISHED;
        }
    }

    /**
     * Despawns every still-live entity the instance spawned (idempotent). The embedder
     * calls this on any end path — finished, errored, stopped, or plugin disable — so no
     * entity ever leaks. Each id is despawned exactly once.
     */
    public void cleanup() {
        for (int id : registry.liveIds()) {
            if (registry.despawn(id)) {
                renderer.despawn(id);
            }
        }
    }

    // --- ABI import implementations ---

    private Map<String, HostFunction> buildImports() {
        Map<String, HostFunction> m = new HashMap<>();
        m.put("billboard.realloc", (ctx, a) ->
                currentTask.allocator.realloc(ctx, (int) a[0], (int) a[1], (int) a[2], (int) a[3]));
        m.put("billboard.fork", (ctx, a) -> {
            throw ctx.suspend(FORK);
        });
        m.put("billboard.join", (ctx, a) -> {
            throw ctx.suspend(new JoinRequest((int) a[0]));
        });
        m.put("billboard.kill", (ctx, a) -> {
            killTask((int) a[0]);
            return 0L;
        });
        m.put("billboard.exit", (ctx, a) -> {
            throw ctx.suspend(EXIT);
        });
        m.put("billboard.sleep", (ctx, a) -> {
            throw ctx.suspend(new SleepRequest(a[0]));
        });
        m.put("billboard.spawn_block_display", (ctx, a) -> spawnBlockDisplay(
                ctx, (int) a[0], (int) a[1], bits(a[2]), bits(a[3]), bits(a[4])));
        m.put("billboard.set_position", (ctx, a) -> {
            int id = (int) a[0];
            registry.setPosition(id, bits(a[1]), bits(a[2]), bits(a[3]));
            renderer.setPosition(id, bits(a[1]), bits(a[2]), bits(a[3]), a[4]);
            return 0L;
        });
        m.put("billboard.set_rotation", (ctx, a) -> {
            int id = (int) a[0];
            registry.setRotation(id, bits(a[1]), bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.setRotation(id, bits(a[1]), bits(a[2]), bits(a[3]), bits(a[4]), a[5]);
            return 0L;
        });
        m.put("billboard.set_scale", (ctx, a) -> {
            int id = (int) a[0];
            registry.setScale(id, bits(a[1]), bits(a[2]), bits(a[3]));
            renderer.setScale(id, bits(a[1]), bits(a[2]), bits(a[3]), a[4]);
            return 0L;
        });
        m.put("billboard.set_block", (ctx, a) -> {
            int id = (int) a[0];
            String block = readString(ctx, (int) a[1], (int) a[2]);
            requireValidBlock(block);
            registry.setBlock(id, block);
            renderer.setBlock(id, block);
            return 0L;
        });
        m.put("billboard.get_position", (ctx, a) -> {
            writeDoubles(ctx, (int) a[1], registry.getPosition((int) a[0]));
            return 0L;
        });
        m.put("billboard.get_rotation", (ctx, a) -> {
            writeDoubles(ctx, (int) a[1], registry.getRotation((int) a[0]));
            return 0L;
        });
        m.put("billboard.get_scale", (ctx, a) -> {
            writeDoubles(ctx, (int) a[1], registry.getScale((int) a[0]));
            return 0L;
        });
        m.put("billboard.get_block_len", (ctx, a) -> {
            pendingBlock = registry.getBlock((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingBlock.length;
        });
        m.put("billboard.get_block", (ctx, a) -> {
            byte[] block = pendingBlock != null
                    ? pendingBlock
                    : registry.getBlock((int) a[0]).getBytes(StandardCharsets.UTF_8);
            ctx.writeBytes((int) a[1], block);
            pendingBlock = null;
            return 0L;
        });
        m.put("billboard.despawn", (ctx, a) -> {
            int id = (int) a[0];
            if (registry.despawn(id)) {
                renderer.despawn(id);
            }
            return 0L;
        });
        m.put("billboard.is_alive", (ctx, a) -> registry.isAlive((int) a[0]) ? 1L : 0L);
        m.put("billboard.log", (ctx, a) -> {
            logSink.log(name, readString(ctx, (int) a[0], (int) a[1]));
            return 0L;
        });
        m.put("billboard.fail", (ctx, a) -> {
            throw new AnimationAbort(readString(ctx, (int) a[0], (int) a[1]));
        });
        return m;
    }

    private long spawnBlockDisplay(ExecutionContext ctx, int ptr, int len, double x, double y, double z) {
        String block = readString(ctx, ptr, len);
        requireValidBlock(block);
        int id = registry.spawn(block, x, y, z);
        renderer.spawnBlockDisplay(id, block, x, y, z);
        return id;
    }

    private void requireValidBlock(String block) {
        if (!validator.isValid(block)) {
            throw new AnimationAbort("invalid block state \"" + block + "\"");
        }
    }

    private static double bits(long raw) {
        return Double.longBitsToDouble(raw);
    }

    private static String readString(ExecutionContext ctx, int ptr, int len) {
        return new String(ctx.readBytes(ptr, len), StandardCharsets.UTF_8);
    }

    private static void writeDoubles(ExecutionContext ctx, int addr, double[] values) {
        for (int i = 0; i < values.length; i++) {
            long b = Double.doubleToRawLongBits(values[i]);
            ctx.storeI32(addr + i * 8, (int) b);
            ctx.storeI32(addr + i * 8 + 4, (int) (b >>> 32));
        }
    }
}
