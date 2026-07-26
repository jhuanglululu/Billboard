package com.jhuanglululu.billboard.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The single MiniMessage parser the plugin uses for guest text — both to validate it in
 * {@link PaperContentValidator} and to render it in
 * {@link PacketEventsRenderer#setText(int, String)}.
 *
 * <p>One instance, deliberately. When validation and rendering each held their own
 * {@code MiniMessage}, any future difference in their configuration (tag resolvers, strictness,
 * placeholders) would let {@code set_text} accept markup the renderer then throws on — a kill from
 * inside the render path, after the registry already recorded the change. Sharing the parser makes
 * that class of disagreement impossible to introduce.
 */
public final class GuestText {

    /**
     * Adventure's default parser. Unclosed tags are valid MiniMessage — {@code "<gray>rest of the
     * line"} is the idiomatic way to style a tail, and the SDK's own demo relies on it — so strict
     * mode is deliberately off. Strict mode would also not help with the failure people expect it
     * to catch: an unknown tag like {@code "<notatag>"} is passed through as literal text in both
     * modes, so a typo is visible either way.
     */
    private static final MiniMessage PARSER = MiniMessage.miniMessage();

    private GuestText() {}

    /** Parses guest markup into a component, throwing on markup MiniMessage rejects. */
    public static Component parse(String miniMessage) {
        return PARSER.deserialize(miniMessage);
    }

    /** Whether {@link #parse} would succeed. */
    public static boolean isValid(String miniMessage) {
        try {
            PARSER.deserialize(miniMessage);
            return true;
        } catch (RuntimeException e) {
            // MiniMessage signals malformed markup with ParsingException, a RuntimeException.
            return false;
        }
    }
}
