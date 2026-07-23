package com.jhuanglululu.billboard.message;

/**
 * Builds the MiniMessage strings for guest output. Pure (no Adventure/Bukkit), so the
 * composition and — importantly — the escaping of untrusted guest text are unit-testable.
 * The Bukkit {@code Messages} helper deserializes these and sends them to audiences.
 */
public final class MessageFormats {

    private MessageFormats() {}

    /** The shared prefix for every Billboard line. */
    public static final String PREFIX = "<gray>[</gray><aqua>Billboard</aqua><gray>]</gray> ";

    /** The owner label for a shared instance (per-player instances use the player's name). */
    public static final String EVERYONE = "EVERYONE";

    /**
     * Neutralizes MiniMessage tags in untrusted text (guest messages, animation names) so a
     * guest can never inject formatting or click/hover actions: a backslash escapes itself
     * and a {@code <} starts a tag, so escaping both is sufficient.
     */
    public static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** {@code [Billboard] <animation> owned by <owner> logged <message>}. */
    public static String guestLog(String animation, String owner, String message) {
        return PREFIX + "<white>" + escape(animation) + "</white> owned by <yellow>" + escape(owner)
                + "</yellow> logged <gray>" + escape(message) + "</gray>";
    }

    /** The error-styled variant for guest {@code fail} / kills. */
    public static String guestFail(String animation, String owner, String message) {
        return PREFIX + "<red><white>" + escape(animation) + "</white> owned by <yellow>" + escape(owner)
                + "</yellow> failed: " + escape(message) + "</red>";
    }
}
