package com.jhuanglululu.billboard.data;

import java.util.Locale;

/** Who may see a placement. Whitelist/blacklist entries are player names or group ids. */
public enum VisibilityMode {

    EVERYONE("everyone"),
    NONE("none"),
    WHITELIST("whitelist"),
    BLACKLIST("blacklist");

    private final String wire;

    VisibilityMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static VisibilityMode fromWire(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        for (VisibilityMode v : values()) {
            if (v.wire.equals(t)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown visibility mode: " + token);
    }
}
