package com.jhuanglululu.billboard.runtime;

import com.jhuanglululu.wasm.HostFunction;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.GuestAbort;
import com.jhuanglululu.wasmachine.runtime.LogSink;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import com.jhuanglululu.wasmachine.runtime.Marshal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One running animation: Billboard's layer over a {@link MachineInstance}. The engine owns the
 * WASM instance, the cooperative tasks, the scheduler, memory, sync and random; this class owns
 * the {@code "billboard"} vocabulary — the entity, sound and particle imports, the
 * {@link EntityRegistry} they mutate, the {@link Renderer} they drive, the validators they
 * enforce — and the <em>meaning</em> of the value {@code main} returns.
 *
 * <p><b>Two namespaces since ABI 3.</b> Engine functions live in the {@code "engine"} module and
 * are WASMachine's to implement; the {@code "billboard"} module is registered here. That split
 * is what keeps a plugin feature from ever being an engine edit.
 *
 * <p><b>Exit codes are plugin semantics.</b> The engine reports {@code Finished(int)} with the
 * raw {@code i32} task 0 returned; {@link #tick} maps it through {@link ExitCode#fromWire} and
 * kills the animation if it is out of range. Nothing else re-interprets an engine result.
 *
 * <p>Not thread-safe: an instance is confined to one worker at a time.
 */
public final class AnimationInstance {

    /**
     * The Billboard ABI version this host speaks: what {@code _billboard_abi} must return.
     * ABI 3 split the import namespaces and ABI 4 added the player imports below; neither is a
     * version a newer guest can fall back to, because the same coordinated break also moved the
     * engine to its own ABI 2 (shared memory, {@code spawn} in place of {@code fork},
     * {@code environ}), which an older guest cannot link against either. There is therefore
     * nothing to be compatible with: min and max are both 4.
     */
    public static final int ABI_VERSION = 4;

    /** The oldest ABI version still accepted — the same one, for the reason above. */
    public static final int MIN_ABI_VERSION = 4;

    /** The plugin's own import module: entities, sound, particles. */
    private static final String MODULE = "billboard";

    /** The engine's import module, owned by WASMachine: tasks, memory, sync, random, math. */
    private static final String ENGINE_MODULE = "engine";

    /** The export task 0 runs every tick — engine-owned, since the engine invokes it. */
    private static final String ENTRY = "_engine_main";

    /** Billboard's handshake export, checked at construction beside the engine's. */
    private static final String ABI_EXPORT = "_billboard_abi";

    /** The engine's handshake export; the version it must report is WASMachine's to state. */
    private static final String ENGINE_ABI_EXPORT = "_engine_abi";

    private final Renderer renderer;
    private final BlockStateValidator validator;
    private final ContentValidator content;
    private final Supplier<List<PlayerView>> players;

    private final EntityRegistry registry = new EntityRegistry();
    private final MachineInstance machine;

    // The two-call get_block / get_item / get_text / players protocols cache the bytes between the
    // paired calls. One field per protocol is enough: there is no blocking point between the two
    // calls, so no other task can run in between and clobber it.
    private byte[] pendingBlock;
    private byte[] pendingItem;
    private byte[] pendingText;
    private byte[] pendingPlayers;

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
     * An instance with no environ, default task stacks and no players — the shape the load-time
     * probe and most tests want, where none of the three can matter.
     */
    public AnimationInstance(String name, Module module, Renderer renderer,
            BlockStateValidator validator, ContentValidator content, LogSink logSink,
            long memoryCapBytes, long instanceSeed) {
        this(name, module, renderer, validator, content, logSink, memoryCapBytes, instanceSeed,
                Map.of(), MachineInstance.Config.DEFAULT_TASK_STACK_BYTES, List::of);
    }

    /**
     * @param name            the animation name (for log routing)
     * @param module          the parsed animation module
     * @param renderer        receives visual side effects
     * @param validator       validates block-state strings
     * @param content         validates item strings and MiniMessage text
     * @param logSink         receives guest {@code log} output
     * @param memoryCapBytes  the per-instance memory cap: one allowance shared by every task's heap
     *                        and by the channel buffers
     * @param instanceSeed    seeds this instance's deterministic random stream; callers derive
     *                        it with {@link #stableSeed} so a placement replays identically
     * @param environ         the effective env for this run (see
     *                        {@link com.jhuanglululu.billboard.data.Env}), read by the guest
     *                        through the engine's {@code environ_len}/{@code environ_read}.
     *                        Immutable for the whole run — an env change restarts the instance
     * @param taskStackBytes  how large a stack the engine gives each spawned task
     * @param players         the latest player snapshot for this instance, in the placement's own
     *                        frame. It is a supplier rather than a list because the main thread
     *                        swaps a fresh one in while this instance ticks on a worker; the host
     *                        functions below read it exactly once per call
     */
    public AnimationInstance(String name, Module module, Renderer renderer,
            BlockStateValidator validator, ContentValidator content, LogSink logSink,
            long memoryCapBytes, long instanceSeed, Map<String, String> environ,
            int taskStackBytes, Supplier<List<PlayerView>> players) {
        this.renderer = renderer;
        this.validator = validator;
        this.content = content;
        this.players = players;
        this.machine = new MachineInstance(module,
                new MachineInstance.Config(name, ENGINE_MODULE, ENTRY,
                        // Both halves of the contract are checked at load time, never at first
                        // use: the engine's, and Billboard's own on top of it.
                        List.of(new MachineInstance.AbiCheck(ENGINE_ABI_EXPORT,
                                        MachineInstance.ENGINE_ABI_VERSION,
                                        MachineInstance.ENGINE_ABI_VERSION),
                                new MachineInstance.AbiCheck(ABI_EXPORT,
                                        MIN_ABI_VERSION, ABI_VERSION)),
                        memoryCapBytes, instanceSeed, environ, taskStackBytes),
                logSink,
                Map.of(MODULE, buildImports()));
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

    /** The animation name. */
    public String name() {
        return machine.name();
    }

    /**
     * The reason this instance is unusable, if construction already decided it — today only a
     * failed handshake, either the engine's {@code _engine_abi} or Billboard's own
     * {@code _billboard_abi}. Load-time validation builds an instance purely to ask this, so the
     * check a server start performs is exactly the one a real start performs; empty means the
     * module is fit to run.
     */
    public Optional<String> loadError() {
        return machine.loadError();
    }

    /**
     * How many times this animation has drawn from the non-deterministic random stream. Zero means
     * every random value it produced is reproducible from its instance seed.
     */
    public long nonDeterministicDraws() {
        return machine.nonDeterministicDraws();
    }

    /** The shared entity registry (host-side source of truth). */
    public EntityRegistry registry() {
        return registry;
    }

    /**
     * Everything the engine can measure about this instance right now — instant gauges plus run
     * totals. Entity counts are deliberately not in it: the engine cannot see them, so
     * {@link #registry()} is where they come from.
     */
    public MachineInstance.StatsSnapshot stats() {
        return machine.stats();
    }

    /**
     * Arms a capture over the next {@code ticks} ticks.
     *
     * @return false if one is already armed, in which case nothing changes
     */
    public boolean startCapture(int ticks) {
        return machine.startCapture(ticks);
    }

    /**
     * Closes an armed capture early, keeping whatever it sampled. The summary that results is
     * marked incomplete, because it is: it covers less than the window asked for.
     *
     * @return false if no capture was armed
     */
    public boolean stopCapture() {
        return machine.stopCapture();
    }

    /** Ticks left on the armed capture, or 0 when none is armed. */
    public long captureRemainingTicks() {
        return machine.captureRemainingTicks();
    }

    /** The most recently finished capture, if one has finished since it was armed. */
    public Optional<MachineInstance.CaptureSummary> captureResult() {
        return machine.captureResult();
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
        return switch (machine.tick(currentTick, fuelBudget)) {
            case com.jhuanglululu.wasmachine.runtime.TickResult.Running ignored ->
                    new TickResult.Running();
            case com.jhuanglululu.wasmachine.runtime.TickResult.Finished f ->
                    ExitCode.fromWire(f.exitValue())
                            .<TickResult>map(TickResult.Finished::new)
                            .orElseGet(() -> new TickResult.Errored(
                                    "main returned invalid exit code " + f.exitValue()));
            case com.jhuanglululu.wasmachine.runtime.TickResult.Errored e ->
                    new TickResult.Errored(e.message());
        };
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

    /**
     * The {@code "billboard"} module: everything the engine does not own. Registering these
     * beside the engine's own imports is the whole extension contract — a new animation
     * capability is a new entry here, never an engine edit.
     */
    private Map<String, HostFunction> buildImports() {
        Map<String, HostFunction> m = new HashMap<>();
        m.put("spawn_block_display", (ctx, a) -> {
            String block = requireValidBlock(Marshal.readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnBlockDisplay(block, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnBlockDisplay(id, block, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("set_position", (ctx, a) -> {
            int id = (int) a[0];
            registry.setPosition(id, bits(a[1]), bits(a[2]), bits(a[3]));
            renderer.setPosition(id, bits(a[1]), bits(a[2]), bits(a[3]), a[4]);
            return 0L;
        });
        m.put("set_rotation", (ctx, a) -> {
            int id = (int) a[0];
            registry.setRotation(id, bits(a[1]), bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.setRotation(id, bits(a[1]), bits(a[2]), bits(a[3]), bits(a[4]), a[5]);
            return 0L;
        });
        m.put("set_scale", (ctx, a) -> {
            int id = (int) a[0];
            registry.setScale(id, bits(a[1]), bits(a[2]), bits(a[3]));
            renderer.setScale(id, bits(a[1]), bits(a[2]), bits(a[3]), a[4]);
            return 0L;
        });
        m.put("set_block", (ctx, a) -> {
            int id = (int) a[0];
            String block = requireValidBlock(Marshal.readString(ctx, (int) a[1], (int) a[2]));
            registry.setBlock(id, block);
            renderer.setBlock(id, block);
            return 0L;
        });
        m.put("get_position", (ctx, a) -> {
            Marshal.writeDoubles(ctx, (int) a[1], registry.getPosition((int) a[0]));
            return 0L;
        });
        m.put("get_rotation", (ctx, a) -> {
            Marshal.writeDoubles(ctx, (int) a[1], registry.getRotation((int) a[0]));
            return 0L;
        });
        m.put("get_scale", (ctx, a) -> {
            Marshal.writeDoubles(ctx, (int) a[1], registry.getScale((int) a[0]));
            return 0L;
        });
        m.put("get_block_len", (ctx, a) -> {
            pendingBlock = registry.getBlock((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingBlock.length;
        });
        m.put("get_block", (ctx, a) -> {
            byte[] block = pendingBlock != null
                    ? pendingBlock
                    : registry.getBlock((int) a[0]).getBytes(StandardCharsets.UTF_8);
            ctx.writeBytes((int) a[1], block);
            pendingBlock = null;
            return 0L;
        });
        m.put("despawn", (ctx, a) -> {
            int id = (int) a[0];
            if (registry.despawn(id)) {
                renderer.despawn(id);
            }
            return 0L;
        });
        m.put("is_alive", (ctx, a) -> registry.isAlive((int) a[0]) ? 1L : 0L);
        addEntityImports(m);
        addEffectImports(m);
        addPlayerImports(m);
        return m;
    }

    /**
     * The ABI-4 player imports: who is watching this placement, where they are and where they are
     * looking — all in the placement's own frame, all read off the snapshot the main thread last
     * handed over. Nothing here touches live server state, because nothing here runs on the main
     * thread.
     *
     * <p>The data is non-deterministic by nature (it depends on who happens to be standing there),
     * so an animation that reads it is no longer replayable from its seed alone — the same caveat
     * the engine's non-deterministic random stream carries.
     */
    private void addPlayerImports(Map<String, HostFunction> m) {
        m.put("players_len", (ctx, a) -> {
            pendingPlayers = PlayerBlob.pack(selectPlayers(ctx, (int) a[0]));
            return pendingPlayers.length;
        });
        m.put("players_read", (ctx, a) -> {
            byte[] blob = pendingPlayers != null
                    ? pendingPlayers
                    : PlayerBlob.pack(selectPlayers(ctx, (int) a[0]));
            ctx.writeBytes((int) a[1], blob);
            pendingPlayers = null;
            return 0L;
        });
        m.put("player_update", (ctx, a) -> {
            String name = Marshal.readString(ctx, (int) a[0], (int) a[1]);
            for (PlayerView p : players.get()) {
                if (p.name().equals(name)) {
                    Marshal.writeDoubles(ctx, (int) a[2], p.values());
                    return 1L;
                }
            }
            // Not a viewer any more: the out buffer is left exactly as the guest had it, so a
            // held Player keeps its last known values rather than being zeroed.
            return 0L;
        });
    }

    /** The players one {@code players_len}/{@code players_read} call selects. */
    private List<PlayerView> selectPlayers(com.jhuanglululu.wasm.ExecutionContext ctx, int queryPtr) {
        return PlayerQuery.parse(ctx.readBytes(queryPtr, PlayerQuery.BYTES)).apply(players.get());
    }

    /**
     * The entity imports beyond block displays: the other four kinds and their attributes. Every
     * attribute op goes through the {@link EntityRegistry}, which kills the animation if the id
     * is the wrong kind for it, before the renderer is told anything.
     */
    private void addEntityImports(Map<String, HostFunction> m) {
        m.put("spawn_item_display", (ctx, a) -> {
            String item = requireValidItem(Marshal.readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnItemDisplay(item, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnItemDisplay(id, item, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("spawn_text_display", (ctx, a) -> {
            String text = requireValidText(Marshal.readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnTextDisplay(text, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnTextDisplay(id, text, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("spawn_armor_stand", (ctx, a) -> {
            int id = registry.spawnArmorStand(bits(a[0]), bits(a[1]), bits(a[2]));
            renderer.spawnArmorStand(id, bits(a[0]), bits(a[1]), bits(a[2]));
            return id;
        });
        m.put("spawn_item", (ctx, a) -> {
            String item = requireValidItem(Marshal.readString(ctx, (int) a[0], (int) a[1]));
            int id = registry.spawnItem(item, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.spawnItem(id, item, bits(a[2]), bits(a[3]), bits(a[4]));
            return id;
        });
        m.put("set_item", (ctx, a) -> {
            int id = (int) a[0];
            String item = requireValidItem(Marshal.readString(ctx, (int) a[1], (int) a[2]));
            registry.setItem(id, item);
            renderer.setItem(id, item);
            return 0L;
        });
        m.put("get_item_len", (ctx, a) -> {
            pendingItem = registry.getItem((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingItem.length;
        });
        m.put("get_item", (ctx, a) -> {
            ctx.writeBytes((int) a[1], pending(pendingItem, registry.getItem((int) a[0])));
            pendingItem = null;
            return 0L;
        });
        m.put("set_display_context", (ctx, a) -> {
            int id = (int) a[0];
            int context = requireRange((int) a[1], 0, 8, "set_display_context", "display context");
            registry.setDisplayContext(id, context);
            renderer.setDisplayContext(id, context);
            return 0L;
        });
        m.put("get_display_context", (ctx, a) -> registry.getDisplayContext((int) a[0]));
        m.put("set_billboard_mode", (ctx, a) -> {
            int id = (int) a[0];
            int mode = requireRange((int) a[1], 0, 3, "set_billboard_mode", "billboard mode");
            registry.setBillboardMode(id, mode);
            renderer.setBillboardMode(id, mode);
            return 0L;
        });
        m.put("get_billboard_mode", (ctx, a) -> registry.getBillboardMode((int) a[0]));
        addTextImports(m);
        addStandImports(m);
    }

    private void addTextImports(Map<String, HostFunction> m) {
        m.put("set_text", (ctx, a) -> {
            int id = (int) a[0];
            String text = requireValidText(Marshal.readString(ctx, (int) a[1], (int) a[2]));
            registry.setText(id, text);
            renderer.setText(id, text);
            return 0L;
        });
        m.put("get_text_len", (ctx, a) -> {
            pendingText = registry.getText((int) a[0]).getBytes(StandardCharsets.UTF_8);
            return pendingText.length;
        });
        m.put("get_text", (ctx, a) -> {
            ctx.writeBytes((int) a[1], pending(pendingText, registry.getText((int) a[0])));
            pendingText = null;
            return 0L;
        });
        m.put("set_text_background", (ctx, a) -> {
            registry.setTextBackground((int) a[0], a[1]);
            renderer.setTextBackground((int) a[0], a[1]);
            return 0L;
        });
        m.put("get_text_background", (ctx, a) -> registry.getTextBackground((int) a[0]));
        m.put("set_text_opacity", (ctx, a) -> {
            long opacity = requireRange(a[1], 0, 255, "set_text_opacity", "opacity");
            registry.setTextOpacity((int) a[0], opacity);
            renderer.setTextOpacity((int) a[0], opacity);
            return 0L;
        });
        m.put("get_text_opacity", (ctx, a) -> registry.getTextOpacity((int) a[0]));
        m.put("set_line_width", (ctx, a) -> {
            registry.setLineWidth((int) a[0], a[1]);
            renderer.setLineWidth((int) a[0], a[1]);
            return 0L;
        });
        m.put("get_line_width", (ctx, a) -> registry.getLineWidth((int) a[0]));
        m.put("set_text_flags", (ctx, a) -> {
            registry.setTextFlags((int) a[0], (int) a[1]);
            renderer.setTextFlags((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put("get_text_flags", (ctx, a) -> registry.getTextFlags((int) a[0]));
    }

    private void addStandImports(Map<String, HostFunction> m) {
        m.put("set_pose", (ctx, a) -> {
            int id = (int) a[0];
            int part = (int) a[1];
            registry.setPose(id, part, bits(a[2]), bits(a[3]), bits(a[4]));
            renderer.setPose(id, part, bits(a[2]), bits(a[3]), bits(a[4]), a[5]);
            return 0L;
        });
        m.put("get_pose", (ctx, a) -> {
            Marshal.writeDoubles(ctx, (int) a[2], registry.getPose((int) a[0], (int) a[1]));
            return 0L;
        });
        m.put("set_equipment", (ctx, a) -> {
            int id = (int) a[0];
            int slot = (int) a[1];
            String item = requireValidItem(Marshal.readString(ctx, (int) a[2], (int) a[3]));
            registry.setEquipment(id, slot, item);
            renderer.setEquipment(id, slot, item);
            return 0L;
        });
        m.put("set_stand_flags", (ctx, a) -> {
            registry.setStandFlags((int) a[0], (int) a[1]);
            renderer.setStandFlags((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put("get_stand_flags", (ctx, a) -> registry.getStandFlags((int) a[0]));
        m.put("set_yaw", (ctx, a) -> {
            registry.setYaw((int) a[0], bits(a[1]));
            renderer.setYaw((int) a[0], bits(a[1]), a[2]);
            return 0L;
        });
        m.put("get_yaw", (ctx, a) -> Marshal.f64Bits(registry.getYaw((int) a[0])));
    }

    /**
     * Sound and particle imports. Sound <em>ids</em> are never validated (the documented
     * exception), but a category outside {@code 0..9} is a guest bug and kills; particle block
     * states and items are validated like everywhere else.
     */
    private void addEffectImports(Map<String, HostFunction> m) {
        m.put("play_sound", (ctx, a) -> {
            String sound = Marshal.readString(ctx, (int) a[0], (int) a[1]);
            int category = requireRange((int) a[5], 0, 9, "play_sound", "sound category");
            renderer.playSound(sound, bits(a[2]), bits(a[3]), bits(a[4]), category,
                    bits(a[6]), bits(a[7]));
            return 0L;
        });
        m.put("emit_particle", (ctx, a) -> emit(
                new ParticleSpec.Named(Marshal.readString(ctx, (int) a[0], (int) a[1])),
                a, 2));
        m.put("emit_particle_dust", (ctx, a) -> emit(
                new ParticleSpec.Dust(bits(a[0]), bits(a[1]), bits(a[2]), bits(a[3])),
                a, 4));
        m.put("emit_particle_dust_transition", (ctx, a) -> emit(
                new ParticleSpec.DustTransition(bits(a[0]), bits(a[1]), bits(a[2]),
                        bits(a[3]), bits(a[4]), bits(a[5]), bits(a[6])),
                a, 7));
        m.put("emit_particle_block", (ctx, a) -> emit(
                new ParticleSpec.Block(
                        requireValidBlock(Marshal.readString(ctx, (int) a[0], (int) a[1]))),
                a, 2));
        m.put("emit_particle_item", (ctx, a) -> emit(
                new ParticleSpec.Item(
                        requireValidItem(Marshal.readString(ctx, (int) a[0], (int) a[1]))),
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

    private String requireValidBlock(String block) {
        if (!validator.isValid(block)) {
            throw new GuestAbort("invalid block state \"" + block + "\"");
        }
        return block;
    }

    private String requireValidItem(String item) {
        if (!content.isValidItem(item)) {
            throw new GuestAbort("invalid item \"" + item + "\"");
        }
        return item;
    }

    private String requireValidText(String text) {
        if (!content.isValidText(text)) {
            throw new GuestAbort("invalid MiniMessage text \"" + text + "\"");
        }
        return text;
    }

    private static long requireRange(long value, long min, long max, String op, String what) {
        if (value < min || value > max) {
            throw new GuestAbort(op + ": " + what + " " + value + " out of range "
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
        return Marshal.f64(raw);
    }
}
