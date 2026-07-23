package com.jhuanglululu.billboard.message;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Computes the effective player recipients of guest log/fail output: the configured
 * log-viewers who are currently online and have not muted themselves. Pure (no Bukkit),
 * so the routing rule is unit-testable; the console is handled separately by a config flag.
 */
public final class LogRecipients {

    private LogRecipients() {}

    /**
     * Filters the configured log-viewers down to those eligible to receive output right now.
     *
     * @param configViewers the {@code log-viewers} from config
     * @param muted         player names that ran {@code /billboard log off}
     * @param onlineNames   names of currently online players
     * @return the config viewers who are online and not muted, in config order
     */
    public static Set<String> effective(Collection<String> configViewers, Set<String> muted,
            Set<String> onlineNames) {
        Set<String> out = new LinkedHashSet<>();
        for (String viewer : configViewers) {
            if (onlineNames.contains(viewer) && !muted.contains(viewer)) {
                out.add(viewer);
            }
        }
        return out;
    }
}
