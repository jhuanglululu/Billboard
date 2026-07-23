package com.jhuanglululu.billboard.message;

import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Routes guest {@code log}/{@code fail} output to exactly the configured {@code log-viewers}
 * plus the console — never the owner or everyone. Call on the main thread (the scheduler
 * flushes buffered guest output there).
 */
public final class GuestOutput {

    private final Server server;
    private final Supplier<List<String>> logViewers;

    public GuestOutput(Server server, Supplier<List<String>> logViewers) {
        this.server = server;
        this.logViewers = logViewers;
    }

    public void log(String animation, String owner, String message) {
        dispatch(MessageFormats.guestLog(animation, owner, message));
    }

    public void fail(String animation, String owner, String message) {
        dispatch(MessageFormats.guestFail(animation, owner, message));
    }

    private void dispatch(String miniMessage) {
        Component component = Messages.render(miniMessage);
        server.getConsoleSender().sendMessage(component);
        for (String name : logViewers.get()) {
            Player p = server.getPlayerExact(name);
            if (p != null) {
                p.sendMessage(component);
            }
        }
    }
}
