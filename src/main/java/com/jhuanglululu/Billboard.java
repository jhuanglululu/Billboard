package com.jhuanglululu;

import com.github.retrooper.packetevents.PacketEvents;
import com.jhuanglululu.billboard.command.BillboardCommand;
import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.config.ConfigLoader;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.message.GuestOutput;
import com.jhuanglululu.billboard.placement.BukkitPositionSource;
import com.jhuanglululu.billboard.placement.ProximityController;
import com.jhuanglululu.billboard.render.PaperBlockStateValidator;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.scheduler.AnimationScheduler;
import com.jhuanglululu.billboard.scheduler.BukkitInstanceLifecycle;
import com.jhuanglululu.billboard.scheduler.RunningInstance;
import com.jhuanglululu.billboard.scheduler.WorkerPoolSizer;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasm.WasmParseException;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Billboard plugin entry point: loads config + animations + persisted data, then wires the
 * pure {@code com.jhuanglululu.billboard.*} runtime (proximity, scheduler, renderer) to the
 * Paper server. All animation {@code .wasm} files are parsed once into shared immutable
 * {@link Module}s.
 */
public final class Billboard extends JavaPlugin {

    private final Map<String, Module> animations = new HashMap<>();
    private BillboardConfig config = BillboardConfig.defaults();
    private DataStore data;
    private Path dataFile;
    private AnimationScheduler scheduler;
    private ProximityController<RunningInstance> controller;
    private long lifecycleTick;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig();
        loadAnimations();
        loadData();

        BlockStateValidator validator = new PaperBlockStateValidator();
        GuestOutput guestOutput = new GuestOutput(getServer(), () -> config.logViewers());
        WorkerPoolSizer sizer = new WorkerPoolSizer(config.runtime().threads(),
                Runtime.getRuntime().availableProcessors(), config.runtime().poolShrinkDelayTicks());
        scheduler = new AnimationScheduler(this, config.runtime().threads(), sizer,
                () -> config.runtime().instructionBudget(), guestOutput);

        BukkitInstanceLifecycle lifecycle = new BukkitInstanceLifecycle(getServer(), scheduler,
                animations::get, validator, () -> config.runtime().memoryCapBytes());
        controller = new ProximityController<>(new BukkitPositionSource(getServer()), lifecycle, data, () -> config);

        scheduler.setEndHandler(controller::forget);
        scheduler.setErrorHandler(this::pauseAnimation);
        scheduler.start();

        int interval = Math.max(1, config.proximity().checkInterval());
        getServer().getScheduler().runTaskTimer(this, () -> {
            lifecycleTick += interval;
            controller.check(lifecycleTick);
        }, interval, interval);

        BillboardCommand.register(this, data, this::saveData, animations::keySet, getServer(), () -> config);

        PacketEvents.getAPI().init();
        getLogger().info("Billboard enabled: " + animations.size() + " animation(s), "
                + data.placements().size() + " placement(s)");
    }

    @Override
    public void onDisable() {
        if (controller != null) {
            controller.stopAll();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (data != null && dataFile != null) {
            saveData();
        }
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        getLogger().info("Billboard disabled");
    }

    private void loadConfig() {
        Path configFile = getDataFolder().toPath().resolve("config.toml");
        if (!Files.exists(configFile)) {
            saveResource("config.toml", false);
        }
        config = ConfigLoader.load(configFile);
    }

    private void loadAnimations() {
        animations.clear();
        Path dir = getDataFolder().toPath().resolve("animations");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            getLogger().severe("Could not create animations folder: " + e.getMessage());
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".wasm")).forEach(this::loadOneAnimation);
        } catch (IOException e) {
            getLogger().severe("Could not scan animations folder: " + e.getMessage());
        }
    }

    private void loadOneAnimation(Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.wasm$", "");
        try {
            animations.put(name, Module.parse(Files.readAllBytes(file)));
        } catch (IOException | WasmParseException e) {
            getLogger().severe("Failed to load animation \"" + name + "\": " + e.getMessage()
                    + " — placements of it are paused until it loads and /billboard resume " + name);
        }
    }

    private void loadData() {
        dataFile = getDataFolder().toPath().resolve("data.toml");
        data = DataStore.load(dataFile);
        // Pause any placement whose animation failed to load, so the scheduler never starts it.
        for (Placement p : data.placements()) {
            if (!animations.containsKey(p.animation())) {
                data.animation(p.animation()).setPaused(true);
            }
        }
    }

    private void saveData() {
        data.save(dataFile);
    }

    private void pauseAnimation(String animation, String message) {
        data.animation(animation).setPaused(true);
        saveData();
        getLogger().severe("Animation \"" + animation + "\" paused after an error: " + message
                + " — clear with /billboard resume " + animation);
    }
}
