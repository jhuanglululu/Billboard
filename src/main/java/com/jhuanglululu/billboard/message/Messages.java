package com.jhuanglululu.billboard.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Thin Adventure wrapper: turns the {@link MessageFormats} MiniMessage strings into
 * components and sends them to audiences (players/console). MiniMessage is bundled with
 * Paper, so no net.kyori dependency is declared.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Messages() {}

    public static Component render(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public static void send(Audience audience, String miniMessage) {
        audience.sendMessage(render(miniMessage));
    }

    /** A visible line carrying {@code hoverMiniMessage} as its hover text (the design's detail-in-hover rule). */
    public static Component withHover(String visibleMiniMessage, String hoverMiniMessage) {
        return render(visibleMiniMessage).hoverEvent(HoverEvent.showText(render(hoverMiniMessage)));
    }

    public static void sendWithHover(Audience audience, String visibleMiniMessage, String hoverMiniMessage) {
        audience.sendMessage(withHover(visibleMiniMessage, hoverMiniMessage));
    }
}
