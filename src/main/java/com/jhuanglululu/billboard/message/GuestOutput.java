package com.jhuanglululu.billboard.message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Routes guest {@code log}/{@code fail} output to the effective log-viewers — the configured
 * {@code log-viewers} who are online and have not muted themselves via {@code /billboard log off}
 * — plus the console when the {@code logging.console} flag is on. Never the owner or everyone.
 * Call on the main thread (the scheduler flushes buffered guest output there).
 */
public final class GuestOutput {

    private final Server server;
    private final Supplier<List<String>> configViewers;
    private final Supplier<Set<String>> muted;
    private final BooleanSupplier consoleEnabled;

    public GuestOutput(Server server, Supplier<List<String>> configViewers,
            Supplier<Set<String>> muted, BooleanSupplier consoleEnabled) {
        this.server = server;
        this.configViewers = configViewers;
        this.muted = muted;
        this.consoleEnabled = consoleEnabled;
    }

    public void log(String animation, String owner, String message) {
        dispatch(MessageFormats.guestLog(animation, owner, message));
    }

    public void fail(String animation, String owner, String message) {
        dispatch(MessageFormats.guestFail(animation, owner, message));
    }

    /**
     * A plugin-level notice (load-time validation), routed like guest output: console when enabled
     * plus the effective log-viewers, with the detail in the hover per the detail-in-hover rule.
     */
    public void issue(String line, String hover) {
        dispatch(Messages.withHover(line, hover));
    }

    private void dispatch(String miniMessage) {
        dispatch(Messages.render(miniMessage));
    }

    private void dispatch(Component component) {
        if (consoleEnabled.getAsBoolean()) {
            server.getConsoleSender().sendMessage(component);
        }
        Set<String> online = new LinkedHashSet<>();
        for (Player p : server.getOnlinePlayers()) {
            online.add(p.getName());
        }
        for (String name : LogRecipients.effective(configViewers.get(), muted.get(), online)) {
            Player p = server.getPlayerExact(name);
            if (p != null) {
                p.sendMessage(component);
            }
        }
    }
}
