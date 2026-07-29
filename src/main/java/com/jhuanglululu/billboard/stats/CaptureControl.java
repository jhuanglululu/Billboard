package com.jhuanglululu.billboard.stats;

import java.util.function.Consumer;

/**
 * How the command drives captures without knowing anything about the scheduler that owns the tick
 * pass. Implemented by the scheduler; the command holds only this.
 */
public interface CaptureControl {

    /**
     * Arms a capture, or reports that one is already running on the same target.
     *
     * @param target      the word the user typed, which is also the one-capture-per-target key
     * @param animation   the resolved animation name
     * @param placementId one placement, or {@code null} for every placement of the animation
     * @param windowTicks how long to capture
     * @param onReport    called once with the finished report, on the main thread
     */
    CaptureOrchestrator.CaptureStart startCapture(String target, String animation,
            String placementId, int windowTicks, Consumer<CaptureReport> onReport);

    /**
     * Closes the capture running on {@code target} now, keeping what it sampled, and delivers the
     * report to whoever started it — the report belongs to the request, not to whoever stopped it.
     *
     * @return false if no capture was running on that target
     */
    boolean stopCapture(String target);
}
