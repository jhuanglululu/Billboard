package com.jhuanglululu.billboard.runtime;

/**
 * Decides whether a block-state string (e.g. {@code "minecraft:oak_stairs[facing=east]"})
 * is valid. The plugin backs this with the server's block-state registry; an invalid
 * state kills the animation (the error philosophy: no silent invisible entities).
 */
@FunctionalInterface
public interface BlockStateValidator {

    boolean isValid(String blockState);
}
