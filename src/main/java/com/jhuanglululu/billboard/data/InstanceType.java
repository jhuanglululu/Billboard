package com.jhuanglululu.billboard.data;

import java.util.Locale;

/** How a placement instantiates instances. */
public enum InstanceType {

    /** One instance per eligible nearby player; only that player sees it. */
    PER_PLAYER("per_player"),
    /** A single instance every eligible viewer shares. */
    SHARED("shared");

    private final String wire;

    InstanceType(String wire) {
        this.wire = wire;
    }

    /** The lowercase token used in commands and data.toml. */
    public String wire() {
        return wire;
    }

    /** Parses the wire token (case-insensitive); throws {@link IllegalArgumentException} if unknown. */
    public static InstanceType fromWire(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        for (InstanceType v : values()) {
            if (v.wire.equals(t)) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown instance type: " + token);
    }
}
