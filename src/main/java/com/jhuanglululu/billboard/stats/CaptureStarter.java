package com.jhuanglululu.billboard.stats;

import java.util.function.Consumer;

/**
 * How the command asks for a capture without knowing anything about the scheduler that owns the
 * tick pass. Implemented by the scheduler; the command holds only this.
 */
@FunctionalInterface
public interface CaptureStarter {

    /**
     * Arms a capture, or reports that one is already running on the same target.
     *
     * @param target      the word the user typed, which is also the one-capture-per-target key
     * @param animation   the resolved animation name
     * @param placementId one placement, or {@code null} for every placement of the animation
     * @param windowTicks how long to capture
     * @param onReport    called once with the finished report, on the main thread
     */
    CaptureOrchestrator.CaptureStart start(String target, String animation, String placementId,
            int windowTicks, Consumer<CaptureReport> onReport);
}
