package com.jhuanglululu.billboard.placement;

import java.util.UUID;

/** A snapshot of one online player's identity and location, as the proximity logic needs it. */
public record ViewerPosition(UUID uuid, String name, String world, double x, double y, double z) {}
