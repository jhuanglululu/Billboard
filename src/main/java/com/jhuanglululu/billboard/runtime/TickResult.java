package com.jhuanglululu.billboard.runtime;

/**
 * The outcome of one {@link AnimationInstance#tick} step.
 *
 * <ul>
 *   <li>{@link Running} — the animation is still going; tick again next game tick.</li>
 *   <li>{@link Finished} — task 0's {@code main} returned; carries its {@link ExitCode}.</li>
 *   <li>{@link Errored} — a trap, {@code fail}, fuel/memory kill, invalid block state, or
 *       dead-entity misuse ended it; carries a precise message. Nothing ever fails
 *       silently.</li>
 * </ul>
 *
 * {@link Finished} and {@link Errored} are terminal: further ticks return the same value.
 */
public sealed interface TickResult permits TickResult.Running, TickResult.Finished, TickResult.Errored {

    record Running() implements TickResult {}

    record Finished(ExitCode exitCode) implements TickResult {}

    record Errored(String message) implements TickResult {}
}
