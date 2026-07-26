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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
 *   <li><b>Releases land in the next round.</b> A tick runs tasks in rounds: each round
 *       gives every due task one turn in spawn order. A task released by a sync operation
 *       (see {@link SyncTable}) is deliberately held back from the round that released it,
 *       so all tasks freed by one barrier completion or one {@code notify_all} take their
 *       turns together, in spawn order, in the following round.</li>
 * </ul>
 *
 * <p>Not thread-safe: an instance is confined to one worker at a time.
 */
public final class AnimationInstance {

    /** The newest ABI version this host speaks; {@code _billboard_abi} may return any accepted one. */
    public static final int ABI_VERSION = 2;

    /** The oldest ABI version still accepted — v2 is purely additive over v1. */
    public static final int MIN_ABI_VERSION = 1;

    private static final long[] NO_ARGS = new long[0];

    // Splits the scheduling random stream off the instance seed (an arbitrary odd constant).
    private static final long SCHEDULING_STREAM_SALT = 0xD1B54A32D192ED03L;

    // Suspension request payloads a host import hands to the interpreter.
    private sealed interface Request
            permits SleepRequest, JoinRequest, ForkRequest, ExitRequest, ParkRequest {}

    private record SleepRequest(long ticks) implements Request {}

    private record JoinRequest(int taskId) implements Request {}

    private record ForkRequest() implements Request {}

    private record ExitRequest() implements Request {}

    /** Parked on a sync object; the {@link SyncTable} decides when (and with what) it resumes. */
    private record ParkRequest() implements Request {}

    private static final ForkRequest FORK = new ForkRequest();
    private static final ExitRequest EXIT = new ExitRequest();
    private static final ParkRequest PARK = new ParkRequest();

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
        /** Parked on a sync object until the {@link SyncTable} releases it. */
        PARKED,
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
        // Set by the sync waker: the value the parked host call returns, and whether the
        // release happened before the park suspension was even recorded (same-turn release).
        long resumeValue;
        boolean parkReleased;

        Task(int id) {
            this.id = id;
        }
    }

    private final String name;
    private final Renderer renderer;
    private final BlockStateValidator validator;
    private final ContentValidator content;
    private final LogSink logSink;

    private final Instance wasm;
    private final EntityRegistry registry = new EntityRegistry();
    private final SyncTable sync;
    // One allowance for every guest heap and every channel buffer in this instance.
    private final MemoryBudget budget;
    // The guest-facing deterministic random stream; seed_random restarts it.
    private final SplitMix64 deterministicRandom;
    // In ascending spawn order: task 0 first, forked children appended as created.
    private final List<Task> tasks = new ArrayList<>();
    // Tasks a sync release woke during the current scheduling round: they wait for the next
    // one, so everything freed by a single release takes its turn in spawn order.
    private final Set<Integer> releasedThisRound = new HashSet<>();

    private int nextTaskId = 1;

    private Task currentTask;
    private long remainingFuel;
    private long tickBudget;

    // How many times the guest drew from the non-deterministic stream. An animation that means to
    // be reproducible must never touch it, and this is the only way to tell from outside: the
    // deterministic and non-deterministic imports are both linked whenever either can be reached.
    private long nonDeterministicDraws;

    // The two-call get_block / get_item / get_text protocols cache the bytes between the paired
    // calls. One field per protocol is enough: there is no blocking point between the two calls,
    // so no other task can run in between and clobber it.
    private byte[] pendingBlock;
    private byte[] pendingItem;
    private byte[] pendingText;

    private TickResult terminal; // set once the animation ends (Finished/Errored)

    /**
     * Convenience constructor for callers with no placement context (tests, tooling): the
     * instance seed is {@code 0}, which is a perfectly valid SplitMix64 seed but of course
     * shared by every such instance, and item/text content is not validated (there is no server
     * to validate it against).
     */
    public AnimationInstance(String name, Module module, Renderer renderer,
            BlockStateValidator validator, LogSink logSink, long memoryCapBytes) {
        this(name, module, renderer, validator, ContentValidator.PERMISSIVE, logSink,
                memoryCapBytes, 0L);
    }

    /**
     * @param name           the animation name (for log routing)
     * @param module         the parsed animation module
     * @param renderer       receives visual side effects
     * @param validator      validates block-state strings
     * @param content        validates item strings and MiniMessage text
     * @param logSink        receives guest {@code log} output
     * @param memoryCapBytes the per-instance memory cap: one allowance shared by every task's heap
     *                       and by the channel buffers (see {@link MemoryBudget})
     * @param instanceSeed   seeds this instance's deterministic random stream; callers derive
     *                       it with {@link #stableSeed} so a placement replays identically
     */
    public AnimationInstance(String name, Module module, Renderer renderer,
            BlockStateValidator validator, ContentValidator content, LogSink logSink,
            long memoryCapBytes, long instanceSeed) {
        this.name = name;
        this.renderer = renderer;
        this.validator = validator;
        this.content = content;
        this.logSink = logSink;
        this.deterministicRandom = new SplitMix64(instanceSeed);
        this.budget = new MemoryBudget(memoryCapBytes);
        // The scheduling stream is derived from the same seed but never shares state with the
        // guest-facing one, so cosmetic random() calls cannot reshuffle notify_one(Random).
        this.sync = new SyncTable(this::releaseTask, instanceSeed ^ SCHEDULING_STREAM_SALT, budget);
        this.wasm = new Instance(module, buildImports());

        ExecutionContext ctx0 = wasm.instantiate();
        int heapBase = exportedGlobalI32(module, ctx0, "__heap_base");

        Task task0 = new Task(0);
        task0.ctx = ctx0;
        task0.allocator = new HostAllocator(heapBase, budget);
        task0.state = TaskState.NOT_STARTED;
        task0.wakeTick = 0;
        tasks.add(task0);

        validateAbi(ctx0);
    }

    private void validateAbi(ExecutionContext ctx0) {
        try {
            currentTask = tasks.get(0);
            ExecResult r = wasm.invoke(ctx0, "_billboard_abi", NO_ARGS, 1_000_000);
            if (!(r instanceof ExecResult.Completed c) || c.values().length != 1) {
                this.terminal = new TickResult.Errored(
                        "ABI handshake failed: _billboard_abi did not return a version");
                return;
            }
            int version = (int) c.values()[0];
            if (version < MIN_ABI_VERSION || version > ABI_VERSION) {
                this.terminal = new TickResult.Errored("ABI handshake failed: _billboard_abi returned "
                        + version + " but this plugin speaks " + MIN_ABI_VERSION + ".." + ABI_VERSION);
            }
        } catch (RuntimeException e) {
            this.terminal = new TickResult.Errored(
                    "module does not export a valid _billboard_abi(): " + e.getMessage());
        }
    }

    /**
     * The stable per-instance random seed: FNV-1a 64 over
     * {@code animation \0 placementId \0 owner}. Deliberately not {@code String#hashCode} or
     * {@code Objects#hash} — this value must survive restarts and JVM changes so a
     * {@code per_player} billboard shows the same player the same variation every visit.
     *
     * @param owner the owner label, or {@code ""} for a shared instance
     */
    public static long stableSeed(String animation, String placementId, String owner) {
        long hash = 0xCBF29CE484222325L;
        for (byte b : (animation + '\0' + placementId + '\0' + owner).getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xFF)) * 0x100000001B3L;
        }
        return hash;
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

    /**
     * The reason this instance is unusable, if construction already decided it — today only a
     * failed {@code _billboard_abi} handshake. Load-time validation builds an instance purely to
     * ask this, so the check a server start performs is exactly the one a real start performs;
     * empty means the module is fit to run.
     */
    public Optional<String> loadError() {
        return terminal instanceof TickResult.Errored e ? Optional.of(e.message()) : Optional.empty();
    }

    /**
     * How many times this animation has drawn from the non-deterministic random stream. Zero means
     * every random value it produced is reproducible from its instance seed.
     */
    public long nonDeterministicDraws() {
        return nonDeterministicDraws;
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
        releasedThisRound.clear();
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
                    releasedThisRound.clear();
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
            if (t.state != TaskState.FINISHED && !ranThisRound.contains(t.id)
                    && !releasedThisRound.contains(t.id) && dueAt(t, currentTick)) {
                return t;
            }
        }
        return null;
    }

    private boolean dueAt(Task t, long currentTick) {
        return switch (t.state) {
            case NOT_STARTED, FORK_PENDING, RUNNABLE -> true;
            case PARKED -> false;
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
                long parkResult = task.resumeValue; // ignored unless the parked call has a result
                task.resumeValue = 0;
                task.parkReleased = false;
                r = wasm.resume(task.ctx, remainingFuel, parkResult);
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
            case ParkRequest ignored -> {
                if (task.parkReleased) {
                    // Released by its own sync call (a barrier its arrival completed): it never
                    // really blocks, but still yields, so the whole group resumes in spawn order.
                    task.parkReleased = false;
                    task.state = TaskState.RUNNABLE;
                } else {
                    task.state = TaskState.PARKED;
                }
                yield null;
            }
            // fork is fully handled in runTurn's inline loop before we get here.
            case ForkRequest ignored -> new TickResult.Errored("stray fork suspension");
        };
    }

    /** The {@link SyncTable.Waker}: a released task becomes runnable in the next round. */
    private void releaseTask(int taskId, long resumeValue) {
        Task t = byId(taskId);
        if (t == null || t.state == TaskState.FINISHED) {
            return;
        }
        t.resumeValue = resumeValue;
        t.parkReleased = true;
        if (t.state == TaskState.PARKED) {
            t.state = TaskState.RUNNABLE;
        }
        releasedThisRound.add(taskId);
    }

    private void finishTask(Task task) {
        task.state = TaskState.FINISHED;
        sync.removeTask(task.id);
        task.allocator.releaseAll(); // the task's memory is gone; give its budget back
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
            // end-of-animation cleanup. Sync state is not orphaned though: a task killed while
            // parked must stop being a waiter and give back any barrier arrival it contributed.
            t.state = TaskState.FINISHED;
            sync.removeTask(id);
            t.allocator.releaseAll();
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
        m.put("billboard.spawn_block_display", (ctx, a) -> {
            String block = requireValidBlock(readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnBlockDisplay(block, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnBlockDisplay(id, block, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
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
            String block = requireValidBlock(readString(ctx, (int) a[1], (int) a[2]));
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
        addEntityImports(m);
        addEffectImports(m);
        addSyncImports(m);
        addRandomImports(m);
        return m;
    }

    /**
     * ABI v2 entity imports: the four new kinds and their attributes. Every attribute op goes
     * through the {@link EntityRegistry}, which kills the animation if the id is the wrong kind
     * for it, before the renderer is told anything.
     */
    private void addEntityImports(Map<String, HostFunction> m) {
        m.put("billboard.spawn_item_display", (ctx, a) -> {
            String item = requireValidItem(readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnItemDisplay(item, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnItemDisplay(id, item, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("billboard.spawn_text_display", (ctx, a) -> {
            String text = requireValidText(readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnTextDisplay(text, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnTextDisplay(id, text, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("billboard.spawn_armor_stand", (ctx, a) -> {
            int id = registry.spawnArmorStand(bits(a[0]), bits(a[1]), bits(a[2]));
            renderer.spawnArmorStand(id, bits(a[0]), bits(a[1]), bits(a[2]));
            return id;
        });
        m.put("billboard.spawn_item", (ctx, a) -> {
            String item = requireValidItem(readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnItem(item, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnItem(id, item, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("billboard.set_item", (ctx, a) -> {
            int id = (int) a[0];
            String item = requireValidItem(readString(ctx, (int) a[1], (int) a[2]));
            registry.setItem(id, item);
            renderer.setItem(id, item);
            return 0L;
        });
        m.put("billboard.get_item_len", (ctx, a) -> {
            pendingItem = registry.getItem((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingItem.length;
        });
        m.put("billboard.get_item", (ctx, a) -> {
            ctx.writeBytes((int) a[1], pending(pendingItem, registry.getItem((int) a[0])));
            pendingItem = null;
            return 0L;
        });
        m.put("billboard.set_display_context", (ctx, a) -> {
            int id = (int) a[0];
            int context = requireRange((int) a[1], 0, 8, "set_display_context", "display context");
            registry.setDisplayContext(id, context);
            renderer.setDisplayContext(id, context);
            return 0L;
        });
        m.put("billboard.get_display_context", (ctx, a) -> registry.getDisplayContext((int) a[0]));
        m.put("billboard.set_billboard_mode", (ctx, a) -> {
            int id = (int) a[0];
            int mode = requireRange((int) a[1], 0, 3, "set_billboard_mode", "billboard mode");
            registry.setBillboardMode(id, mode);
            renderer.setBillboardMode(id, mode);
            return 0L;
        });
        m.put("billboard.get_billboard_mode", (ctx, a) -> registry.getBillboardMode((int) a[0]));
        addTextImports(m);
        addStandImports(m);
    }

    private void addTextImports(Map<String, HostFunction> m) {
        m.put("billboard.set_text", (ctx, a) -> {
            int id = (int) a[0];
            String text = requireValidText(readString(ctx, (int) a[1], (int) a[2]));
            registry.setText(id, text);
            renderer.setText(id, text);
            return 0L;
        });
        m.put("billboard.get_text_len", (ctx, a) -> {
            pendingText = registry.getText((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingText.length;
        });
        m.put("billboard.get_text", (ctx, a) -> {
            ctx.writeBytes((int) a[1], pending(pendingText, registry.getText((int) a[0])));
            pendingText = null;
            return 0L;
        });
        m.put("billboard.set_text_background", (ctx, a) -> {
            registry.setTextBackground((int) a[0], a[1]);
            renderer.setTextBackground((int) a[0], a[1]);
            return 0L;
        });
        m.put("billboard.get_text_background", (ctx, a) -> registry.getTextBackground((int) a[0]));
        m.put("billboard.set_text_opacity", (ctx, a) -> {
            long opacity = requireRange(a[1], 0, 255, "set_text_opacity", "opacity");
            registry.setTextOpacity((int) a[0], opacity);
            renderer.setTextOpacity((int) a[0], opacity);
            return 0L;
        });
        m.put("billboard.get_text_opacity", (ctx, a) -> registry.getTextOpacity((int) a[0]));
        m.put("billboard.set_line_width", (ctx, a) -> {
            registry.setLineWidth((int) a[0], a[1]);
            renderer.setLineWidth((int) a[0], a[1]);
            return 0L;
        });
        m.put("billboard.get_line_width", (ctx, a) -> registry.getLineWidth((int) a[0]));
        m.put("billboard.set_text_flags", (ctx, a) -> {
            registry.setTextFlags((int) a[0], (int) a[1]);
            renderer.setTextFlags((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put("billboard.get_text_flags", (ctx, a) -> registry.getTextFlags((int) a[0]));
    }

    private void addStandImports(Map<String, HostFunction> m) {
        m.put("billboard.set_pose", (ctx, a) -> {
            int id = (int) a[0];
            int part = (int) a[1];
            registry.setPose(id, part, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.setPose(id, part, bits(a[2]), bits(a[3]), bits(a[4]), a[5]);
            return 0L;
        });
        m.put("billboard.get_pose", (ctx, a) -> {
            writeDoubles(ctx, (int) a[2], registry.getPose((int) a[0], (int) a[1]));
            return 0L;
        });
        m.put("billboard.set_equipment", (ctx, a) -> {
            int id = (int) a[0];
            int slot = (int) a[1];
            String item = requireValidItem(readString(ctx, (int) a[2], (int) a[3]));
            registry.setEquipment(id, slot, item);
            renderer.setEquipment(id, slot, item);
            return 0L;
        });
        m.put("billboard.set_stand_flags", (ctx, a) -> {
            registry.setStandFlags((int) a[0], (int) a[1]);
            renderer.setStandFlags((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put("billboard.get_stand_flags", (ctx, a) -> registry.getStandFlags((int) a[0]));
        m.put("billboard.set_yaw", (ctx, a) -> {
            registry.setYaw((int) a[0], bits(a[1]));
            renderer.setYaw((int) a[0], bits(a[1]), a[2]);
            return 0L;
        });
        m.put("billboard.get_yaw", (ctx, a) ->
                Double.doubleToRawLongBits(registry.getYaw((int) a[0])));
    }

    /**
     * ABI v2 sound and particle imports. Sound <em>ids</em> are never validated (the documented
     * exception), but a category outside {@code 0..9} is a guest bug and kills; particle block
     * states and items are validated like everywhere else.
     */
    private void addEffectImports(Map<String, HostFunction> m) {
        m.put("billboard.play_sound", (ctx, a) -> {
            String sound = readString(ctx, (int) a[0], (int) a[1]);
            int category = requireRange((int) a[5], 0, 9, "play_sound", "sound category");
            renderer.playSound(sound, bits(a[2]), bits(a[3]), bits(a[4]), category,
                    bits(a[6]), bits(a[7]));
            return 0L;
        });
        m.put("billboard.emit_particle", (ctx, a) -> emit(
                new ParticleSpec.Named(readString(ctx, (int) a[0], (int) a[1])),
                a, 2));
        m.put("billboard.emit_particle_dust", (ctx, a) -> emit(
                new ParticleSpec.Dust(bits(a[0]), bits(a[1]), bits(a[2]), bits(a[3])),
                a, 4));
        m.put("billboard.emit_particle_dust_transition", (ctx, a) -> emit(
                new ParticleSpec.DustTransition(bits(a[0]), bits(a[1]), bits(a[2]),
                        bits(a[3]), bits(a[4]), bits(a[5]), bits(a[6])),
                a, 7));
        m.put("billboard.emit_particle_block", (ctx, a) -> emit(
                new ParticleSpec.Block(requireValidBlock(readString(ctx, (int) a[0], (int) a[1]))),
                a, 2));
        m.put("billboard.emit_particle_item", (ctx, a) -> emit(
                new ParticleSpec.Item(requireValidItem(readString(ctx, (int) a[0], (int) a[1]))),
                a, 2));
    }

    /**
     * The tail every {@code emit_particle_*} import shares, starting at argument {@code at}:
     * {@code x, y, z: f64, count: i32, ox, oy, oz: f64, speed: f64}.
     */
    private long emit(ParticleSpec particle, long[] a, int at) {
        renderer.emitParticle(new ParticleSpec.Emission(particle,
                bits(a[at]), bits(a[at + 1]), bits(a[at + 2]), (int) a[at + 3],
                bits(a[at + 4]), bits(a[at + 5]), bits(a[at + 6]), bits(a[at + 7])));
        return 0L;
    }

    /** ABI v2 sync imports. Every one of these kills the animation on a bad id or kind. */
    private void addSyncImports(Map<String, HostFunction> m) {
        m.put("billboard.signal_new", (ctx, a) -> sync.newSignal());
        m.put("billboard.signal_notify", (ctx, a) -> {
            sync.notifySignal((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put("billboard.barrier_new", (ctx, a) -> sync.newBarrier((int) a[0]));
        m.put("billboard.wait_all", (ctx, a) -> sync.newComposite(true, (int) a[0], (int) a[1]));
        m.put("billboard.wait_any", (ctx, a) -> sync.newComposite(false, (int) a[0], (int) a[1]));
        m.put("billboard.wait", (ctx, a) -> {
            // Always suspends: a wait satisfied on the spot is released through the waker, so
            // it yields its turn like every other release instead of running straight on.
            sync.park(currentTask.id, (int) a[0]);
            throw ctx.suspend(PARK);
        });
        m.put("billboard.channel_new", (ctx, a) -> sync.newChannel((int) a[0]));
        m.put("billboard.channel_send", (ctx, a) -> blocking(ctx,
                sync.send(currentTask.id, (int) a[0], ctx.readBytes((int) a[1], (int) a[2]))));
        m.put("billboard.channel_recv_len", (ctx, a) ->
                blocking(ctx, sync.receiveLength(currentTask.id, (int) a[0], false)));
        m.put("billboard.channel_recv", (ctx, a) -> {
            ctx.writeBytes((int) a[1], sync.receive(currentTask.id, (int) a[0]));
            return 0L;
        });
        m.put("billboard.channel_peek_len", (ctx, a) ->
                blocking(ctx, sync.receiveLength(currentTask.id, (int) a[0], true)));
        m.put("billboard.channel_peek", (ctx, a) -> {
            ctx.writeBytes((int) a[1], sync.peek(currentTask.id, (int) a[0]));
            return 0L;
        });
        m.put("billboard.channel_try_len", (ctx, a) -> sync.tryLength(currentTask.id, (int) a[0]));
        m.put("billboard.channel_clear", (ctx, a) -> {
            sync.clear((int) a[0]);
            return 0L;
        });
    }

    /** ABI v2 random imports: one non-deterministic stream, one seeded per-instance stream. */
    private void addRandomImports(Map<String, HostFunction> m) {
        m.put("billboard.random_nondet", (ctx, a) -> {
            nonDeterministicDraws++;
            return ThreadLocalRandom.current().nextLong();
        });
        m.put("billboard.random_det", (ctx, a) -> deterministicRandom.nextLong());
        m.put("billboard.seed_random", (ctx, a) -> {
            deterministicRandom.reseed(a[0]);
            return 0L;
        });
    }

    /** Parks the calling task if the sync operation could not complete inline. */
    private static long blocking(ExecutionContext ctx, SyncTable.SyncOutcome outcome) {
        if (outcome.parked()) {
            throw ctx.suspend(PARK);
        }
        return outcome.value();
    }

    private String requireValidBlock(String block) {
        if (!validator.isValid(block)) {
            throw new AnimationAbort("invalid block state \"" + block + "\"");
        }
        return block;
    }

    private String requireValidItem(String item) {
        if (!content.isValidItem(item)) {
            throw new AnimationAbort("invalid item \"" + item + "\"");
        }
        return item;
    }

    private String requireValidText(String text) {
        if (!content.isValidText(text)) {
            throw new AnimationAbort("invalid MiniMessage text \"" + text + "\"");
        }
        return text;
    }

    private static long requireRange(long value, long min, long max, String op, String what) {
        if (value < min || value > max) {
            throw new AnimationAbort(op + ": " + what + " " + value + " out of range "
                    + min + ".." + max);
        }
        return value;
    }

    private static int requireRange(int value, int min, int max, String op, String what) {
        return (int) requireRange((long) value, min, max, op, what);
    }

    /** The bytes a {@code *_len} call cached, or a fresh read if the guest skipped it. */
    private static byte[] pending(byte[] cached, String current) {
        return cached != null ? cached : current.getBytes(StandardCharsets.UTF_8);
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
