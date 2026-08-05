package com.jhuanglululu.billboard.config;

import java.util.List;

/**
 * The operator-facing configuration (config.toml), parsed into immutable values.
 * config.toml is read-only for the plugin.
 *
 * @param consoleLog whether the console also receives guest log/fail output (player
 *     log-viewers always do); plugin lifecycle/error messages are unaffected
 */
public record BillboardConfig(RuntimeSettings runtime, Proximity proximity, List<String> logViewers,
        boolean consoleLog) {

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
     */
    public record RuntimeSettings(int threads, int poolShrinkDelayTicks, long instructionBudget,
            int memoryCapMib) {

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

    /** The built-in defaults, matching the shipped config.toml template. */
    public static BillboardConfig defaults() {
        return new BillboardConfig(
                new RuntimeSettings(4, 200, 1_000_000L, 16),
                new Proximity(64, 20, 100),
                List.of(),
                true);
    }
}
