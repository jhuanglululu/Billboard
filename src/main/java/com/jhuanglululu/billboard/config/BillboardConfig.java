package com.jhuanglululu.billboard.config;

import java.util.List;

/**
 * The operator-facing configuration (config.toml), parsed into immutable values.
 * config.toml is read-only for the plugin.
 *
 * @param consoleLog whether the console also receives guest log/fail output (player
 *     log-viewers always do); plugin lifecycle/error messages are unaffected
 */
public record BillboardConfig(RuntimeSettings runtime, Proximity proximity, Snapshots snapshots,
        List<String> logViewers, boolean consoleLog) {

    public BillboardConfig {
        logViewers = List.copyOf(logViewers);
    }

    /**
     * Interpreter/threading knobs.
     *
     * @param threads              hard cap on interpreter worker threads
     * @param poolShrinkDelayTicks debounce before the pool shrinks
     * @param instructionBudget    per-instance per-tick instruction budget
     * @param memoryCapMib         per-instance guest memory cap, in MiB
     * @param taskStackBytes       how large a stack region the engine carves out of the shared heap
     *                             for each task {@code spawn} creates (engine ABI 2; task 0 keeps
     *                             the stack the linker gave the module). It is charged against
     *                             {@code memoryCapMib} like everything else
     */
    public record RuntimeSettings(int threads, int poolShrinkDelayTicks, long instructionBudget,
            int memoryCapMib, int taskStackBytes) {

        /** The memory cap in bytes, for {@code AnimationInstance}. */
        public long memoryCapBytes() {
            return (long) memoryCapMib * 1024L * 1024L;
        }
    }

    /**
     * Proximity-lifecycle knobs.
     *
     * @param radius        proximity radius in blocks
     * @param checkInterval how often (ticks) proximity is re-checked
     * @param lingerTicks   how long an out-of-range instance lingers before dying
     */
    public record Proximity(int radius, int checkInterval, int lingerTicks) {}

    /**
     * How often the main thread hands running instances fresh player data.
     *
     * @param playerInterval how often (ticks) each instance's player snapshot list is rebuilt and
     *                       swapped in. The default is every tick: the guest's {@code players()}
     *                       exists to drive per-tick following, and anything slower shows as
     *                       visible lag. Raise it if a server has many instances and can live with
     *                       staler positions
     */
    public record Snapshots(int playerInterval) {}

    /** The built-in defaults, matching the shipped config.toml template. */
    public static BillboardConfig defaults() {
        return new BillboardConfig(
                new RuntimeSettings(4, 200, 1_000_000L, 16, 64 * 1024),
                // The proximity check moved from every second to every half-second with the player
                // snapshots: an instance that follows a player should not take a second to notice
                // one arriving.
                new Proximity(64, 10, 100),
                new Snapshots(1),
                List.of(),
                true);
    }
}
