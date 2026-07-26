package com.jhuanglululu.billboard.runtime;

/**
 * Validates the two v2 content strings the server owns the grammar for: item stacks in the
 * vanilla {@code /give} component format, and MiniMessage text. The plugin backs both with the
 * server's own parsers (Paper's {@code ItemFactory#createItemStack} and the MiniMessage
 * deserializer), because only the server can say exactly what is valid.
 *
 * <p>Invalid content kills the animation, like an invalid block state — the runtime checks
 * before the renderer is told anything, so a typo can never become an invisible entity.
 *
 * <p>Sound ids are deliberately absent: they are never validated (resolution is client-side and
 * resource packs extend the namespace), the one documented exception to the error philosophy.
 */
public interface ContentValidator {

    /** A validator that accepts everything, for headless tests and tooling with no server. */
    ContentValidator PERMISSIVE = new ContentValidator() {

        @Override
        public boolean isValidItem(String item) {
            return true;
        }

        @Override
        public boolean isValidText(String miniMessage) {
            return true;
        }
    };

    /** Whether {@code item} parses as a vanilla item stack with components. */
    boolean isValidItem(String item);

    /** Whether {@code miniMessage} parses as MiniMessage markup. */
    boolean isValidText(String miniMessage);
}
