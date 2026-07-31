package com.jhuanglululu.billboard.load;

import com.jhuanglululu.billboard.message.MessageFormats;

/**
 * One thing that failed to load, and what was skipped because of it. Every issue is loud: a short
 * MiniMessage line for chat/console with the detail in its hover, per the design's
 * detail-in-hover rule. Pure, so both the wording and the escaping of untrusted names (file names,
 * placement ids) are unit-testable.
 *
 * @param scope   what was skipped — an animation or a single placement
 * @param subject the animation name, or {@code animation/id} for a placement
 * @param detail  the human-readable reason
 */
public record LoadIssue(Scope scope, String subject, String detail) {

    /** What a failure takes out of service. */
    public enum Scope {
        /** The whole animation: it did not parse or failed the ABI handshake. */
        ANIMATION("animation"),
        /** One placement: its animation, world, or a visibility entry is unusable. */
        PLACEMENT("placement");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static LoadIssue animation(String name, String detail) {
        return new LoadIssue(Scope.ANIMATION, name, detail);
    }

    public static LoadIssue placement(String key, String detail) {
        return new LoadIssue(Scope.PLACEMENT, key, detail);
    }

    /** The visible line: what was skipped, never the whole reason. */
    public String line() {
        return MessageFormats.PREFIX + "<red>Skipped " + scope.label() + " <white>"
                + MessageFormats.escape(subject) + "</white></red>";
    }

    /** The hover detail: how to clear it, then the reason. */
    public String hover() {
        return "<gray>fix it and run /billboard reload</gray>"
                + "\n<red>" + MessageFormats.escape(detail) + "</red>";
    }

    /** The console/log one-liner (no MiniMessage), for {@code getLogger}. */
    public String plain() {
        return "Skipped " + scope.label() + " \"" + subject + "\": " + detail;
    }
}
