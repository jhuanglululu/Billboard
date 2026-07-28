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

    /**
     * {@code [Billboard] <animation>/<id> is paused} — the one-time nudge an admin or log-viewer
     * gets on walking into range of a placement that will never start. Without it a pause is
     * silent at the only moment it matters: standing in front of the empty spot.
     */
    public static String pauseHint(String animation, String id) {
        return PREFIX + "<white>" + escape(animation) + "/" + escape(id) + "</white> <red>is paused</red>";
    }

    /**
     * The hover detail for {@link #pauseHint}: which flag holds it and the exact command that
     * clears that flag — {@code resume <animation>} for the animation-level pause (which stops
     * every placement of it), {@code resume <id>} for this placement alone.
     */
    public static String pauseHintDetail(String animation, String id, boolean animationLevel) {
        if (animationLevel) {
            return "<gray>the animation <white>" + escape(animation)
                    + "</white> is paused, so none of its placements run</gray>"
                    + "\n<gray>resume with <white>/billboard resume " + escape(animation) + "</white></gray>";
        }
        return "<gray>this placement is paused; other placements of <white>" + escape(animation)
                + "</white> still run</gray>"
                + "\n<gray>resume with <white>/billboard resume " + escape(id) + "</white></gray>";
    }
}
