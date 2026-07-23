package com.jhuanglululu.billboard.render;

import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import org.bukkit.Bukkit;

/**
 * Validates block-state strings against the server via {@link Bukkit#createBlockData(String)},
 * which throws {@link IllegalArgumentException} for anything unparseable or unknown.
 *
 * <p>Threading: {@code createBlockData} only reads the immutable, load-time block registry
 * and parses a string, so it is safe to call from the interpreter worker threads (it does
 * not touch mutable world state). The interpreter calls this before the renderer ever sees
 * a block, upholding the error philosophy — no silent invisible entities.
 */
public final class PaperBlockStateValidator implements BlockStateValidator {

    @Override
    public boolean isValid(String blockState) {
        try {
            Bukkit.createBlockData(blockState);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
