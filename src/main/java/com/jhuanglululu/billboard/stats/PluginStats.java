package com.jhuanglululu.billboard.stats;

import java.util.Map;

/**
 * The instant plugin-wide view {@code /billboard stats} shows with no arguments: what the runtime
 * is doing right now, with no capture and no waiting.
 *
 * @param poolThreads      interpreter worker threads in the pool right now
 * @param maxThreads       the configured hard cap the pool may grow to
 * @param activeInstances  instances currently ticking
 * @param instancesByAnimation live instance count per animation, animations with none omitted
 * @param placements       persisted placements, running or not
 */
public record PluginStats(int poolThreads, int maxThreads, int activeInstances,
        Map<String, Integer> instancesByAnimation, int placements) {

    public PluginStats {
        instancesByAnimation = Map.copyOf(instancesByAnimation);
    }
}
