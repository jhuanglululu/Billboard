package com.jhuanglululu.billboard.runtime;

import java.util.Optional;

/**
 * What an animation's {@code main} returns to tell the host what to do when it ends
 * (task 0 returning ends the whole animation). Crosses the ABI as the {@code i32}
 * result of {@code _billboard_main}: {@code End = 0}, {@code Keep = 1}, {@code Repeat = 2}.
 */
public enum ExitCode {

    /** Clear everything, including leaked entities. */
    END,
    /** Release runtime state but keep leaked entities visible while viewers are near. */
    KEEP,
    /** Clear everything, then restart immediately. */
    REPEAT;

    /** Maps the ABI wire value to an {@link ExitCode}, or empty if it is out of range. */
    public static Optional<ExitCode> fromWire(int value) {
        return switch (value) {
            case 0 -> Optional.of(END);
            case 1 -> Optional.of(KEEP);
            case 2 -> Optional.of(REPEAT);
            default -> Optional.empty();
        };
    }
}
