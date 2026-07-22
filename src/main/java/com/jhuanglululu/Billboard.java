package com.jhuanglululu;

import org.bukkit.plugin.java.JavaPlugin;

public final class Billboard extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Billboard enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Billboard disabled");
    }
}
