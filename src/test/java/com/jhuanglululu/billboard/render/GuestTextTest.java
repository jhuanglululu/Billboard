package com.jhuanglululu.billboard.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * The MiniMessage boundary. The point of {@link GuestText} is that validation and rendering go
 * through <em>one</em> parser instance, so {@code set_text} can never accept markup the renderer
 * then fails on (or vice versa) — the two used to be independent {@code MiniMessage} instances.
 */
class GuestTextTest {

    /** Payloads the v2 demo fixture actually sends; all of these must stay valid. */
    private static final String[] DEMO_PAYLOADS = {
        "<bold><gradient:#f1af15:#e06100>NOW SHOWING</gradient></bold>",
        "<italic><gray>that's all, folks",
        "** NOW SHOWI",
        "",
        "THANKS FOR",
    };

    @Test
    void validationAndRenderingAgreeOnEveryInput() {
        String[] samples = {
            "plain text", "<red>closed</red>", "<red>trailing style", "<bold><italic>nested</italic>",
            "<gradient:#ff0000:#00ff00>ramp</gradient>", "<notatag>", "", "<click>",
            "</red>", "<gradient:>", "<hover:show_text:'hi'>x</hover>",
        };
        for (String sample : samples) {
            boolean valid = GuestText.isValid(sample);
            if (valid) {
                assertNotNull(GuestText.parse(sample), "validated but did not render: " + sample);
            } else {
                // Rejected input must also fail to render, or the two would disagree.
                assertFalse(tryRender(sample), "rejected but rendered anyway: " + sample);
            }
        }
    }

    private static boolean tryRender(String miniMessage) {
        try {
            GuestText.parse(miniMessage);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void everyDemoFixturePayloadIsAccepted() {
        for (String payload : DEMO_PAYLOADS) {
            assertTrue(GuestText.isValid(payload), "the demo fixture sends this: " + payload);
        }
    }

    @Test
    void trailingStylesAreValidMiniMessage() {
        // Styling the rest of a line without closing the tag is idiomatic MiniMessage and the demo
        // relies on it, so this must not be treated as an error.
        assertTrue(GuestText.isValid("<italic><gray>that's all, folks"));
        assertEquals("that's all, folks",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(GuestText.parse("<italic><gray>that's all, folks")));
    }

    @Test
    void parsedTextKeepsItsContent() {
        Component parsed = GuestText.parse("<bold>NOW SHOWING</bold>");
        assertEquals("NOW SHOWING",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(parsed));
    }
}
