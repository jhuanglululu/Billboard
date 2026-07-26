package com.jhuanglululu.billboard.render;

import com.jhuanglululu.billboard.runtime.ContentValidator;
import org.bukkit.Bukkit;

/**
 * Validates item strings and MiniMessage text with the server's own parsers — Paper's
 * {@code ItemFactory#createItemStack} (the full vanilla {@code /give} component format) and the
 * shared MiniMessage parser in {@link GuestText}. Only the server can say exactly which components
 * a given item accepts, so nothing is reimplemented here.
 *
 * <p>Threading: both parsers read immutable load-time registries and a string, so they are safe on
 * the interpreter worker threads, like {@link PaperBlockStateValidator}. The runtime calls this
 * before the renderer ever sees the value, upholding the error philosophy.
 */
public final class PaperContentValidator implements ContentValidator {

    @Override
    public boolean isValidItem(String item) {
        try {
            Bukkit.getItemFactory().createItemStack(item);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean isValidText(String miniMessage) {
        // The very parser the renderer will use, so the two can never disagree (see GuestText).
        return GuestText.isValid(miniMessage);
    }
}
