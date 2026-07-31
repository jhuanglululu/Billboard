package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.message.MessageFormats;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Guards {@code /billboard spawn} against the arguments it cannot use: an animation whose module
 * never loaded, a coordinate token that is not a coordinate, and {@code ~} from a sender that has
 * no position to measure from. Pure (no Bukkit), so it is unit-testable.
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

    /**
     * The one shape a coordinate token that is neither a number nor a {@code ~} form gets, with
     * the offending token in white like every other rejected word.
     */
    public static String badCoordinate(String axis, String token) {
        return MessageFormats.PREFIX + "<red>Bad " + axis + " coordinate: <white>"
                + MessageFormats.escape(token) + "</white></red>";
    }

    /**
     * What a console (or any senderless source) gets for {@code ~}: there is no position to be
     * relative to, and guessing one would put the placement somewhere nobody chose.
     */
    public static String relativeNeedsPlayer() {
        return MessageFormats.PREFIX + "<red>Only a player can use <white>~</white> coordinates — "
                + "type the numbers instead</red>";
    }
}
