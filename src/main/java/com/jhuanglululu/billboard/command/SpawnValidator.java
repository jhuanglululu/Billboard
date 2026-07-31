package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.message.MessageFormats;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Guards {@code /billboard spawn} against unknown animations: a placement may only be created
 * for an animation whose module actually loaded. Pure (no Bukkit), so it is unit-testable.
 */
public final class SpawnValidator {

    private SpawnValidator() {}

    /**
     * Rejects an animation name that is not among the loaded modules.
     *
     * @return an error MiniMessage line (listing the loaded animations) if {@code animation} is
     *     not among {@code loaded}, or empty if it is a valid, loaded animation
     */
    public static Optional<String> rejectUnknown(String animation, Collection<String> loaded) {
        if (loaded.contains(animation)) {
            return Optional.empty();
        }
        String names = loaded.isEmpty()
                ? "(none)"
                : loaded.stream().map(MessageFormats::escape).collect(Collectors.joining(", "));
        return Optional.of(MessageFormats.PREFIX + "<red>Unknown animation <white>"
                + MessageFormats.escape(animation)
                + "</white> — not loaded. Loaded: <gray>" + names + "</gray></red>");
    }
}
