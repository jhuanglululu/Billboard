package com.jhuanglululu.billboard.runtime;

/**
 * Thrown by a host import to abort the whole animation with a message: {@code fail},
 * an invalid block state, use of a dead/unknown entity, or an allocator failure. The
 * {@link AnimationInstance} catches it and turns it into a {@link TickResult.Errored}.
 * It is a plain error signal, not caught by the interpreter's suspend machinery.
 */
public class AnimationAbort extends RuntimeException {

    public AnimationAbort(String message) {
        super(message);
    }
}
