package com.jhuanglululu;

import com.github.retrooper.packetevents.PacketEvents;
import com.jhuanglululu.billboard.command.BillboardCommand;
import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.config.ConfigLoader;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.load.AnimationReloadDiff;
import com.jhuanglululu.billboard.load.ReloadSummary;
import com.jhuanglululu.billboard.message.GuestOutput;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.message.Messages;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
    private final Map<String, Integer> animationHashes = new HashMap<>();
    private BillboardConfig config = BillboardConfig.defaults();
    private DataStore data;
    private Path dataFile;
    private AnimationScheduler scheduler;
    private ProximityController<RunningInstance> controller;
    private long lifecycleTick;

    private record Scan(Map<String, Module> modules, Map<String, Integer> hashes, List<String> errors) {}

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
        controller.setStartFailureHandler((animation, message) ->
                pauseAnimation(animation, "could not start instance: " + message));
        scheduler.start();

        int interval = Math.max(1, config.proximity().checkInterval());
        getServer().getScheduler().runTaskTimer(this, () -> {
            lifecycleTick += interval;
            controller.check(lifecycleTick);
        }, interval, interval);

        BillboardCommand.register(this, data, this::saveData, animations::keySet, getServer(),
                () -> config, this::reload);

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

    /** Scans and parses every {@code .wasm} in the animations folder; never throws. */
    private Scan scan() {
        Map<String, Module> modules = new HashMap<>();
        Map<String, Integer> hashes = new HashMap<>();
        List<String> errors = new ArrayList<>();
        Path dir = getDataFolder().toPath().resolve("animations");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            errors.add("cannot create animations folder: " + e.getMessage());
            return new Scan(modules, hashes, errors);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".wasm")).sorted().forEach(file -> {
                String name = file.getFileName().toString().replaceFirst("\\.wasm$", "");
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    modules.put(name, Module.parse(bytes));
                    hashes.put(name, Arrays.hashCode(bytes));
                } catch (IOException | WasmParseException e) {
                    errors.add(name + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            errors.add("cannot scan animations folder: " + e.getMessage());
        }
        return new Scan(modules, hashes, errors);
    }

    private void loadAnimations() {
        Scan scan = scan();
        animations.clear();
        animations.putAll(scan.modules());
        animationHashes.clear();
        animationHashes.putAll(scan.hashes());
        for (String error : scan.errors()) {
            getLogger().severe("Animation load failed — " + error
                    + " (paused; fix and /billboard reload then /billboard resume)");
        }
    }

    /** Rescan the folder, restart changed/removed animations' instances, report a summary. */
    public ReloadSummary reload() {
        Scan scan = scan();
        AnimationReloadDiff diff = AnimationReloadDiff.compute(animationHashes, scan.hashes());
        for (String name : diff.stopped()) {
            controller.stopInstancesOf(name); // changed/removed: stop (cleanup); proximity restarts if still present
        }
        animations.clear();
        animations.putAll(scan.modules());
        animationHashes.clear();
        animationHashes.putAll(scan.hashes());
        for (String removed : diff.removed()) {
            data.animation(removed).setPaused(true); // no module -> keep it from instantiating
        }
        saveData();
        for (String error : scan.errors()) {
            getLogger().severe("Reload — " + error);
        }
        getLogger().info("Reloaded animations: +" + diff.added().size() + " ~" + diff.changed().size()
                + " -" + diff.removed().size() + (scan.errors().isEmpty() ? "" : " (" + scan.errors().size() + " error(s))"));
        return new ReloadSummary(diff, scan.errors());
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

    /** Pause an animation after an error and report it once (loud: console MiniMessage + log). */
    private void pauseAnimation(String animation, String message) {
        data.animation(animation).setPaused(true);
        saveData();
        String line = MessageFormats.PREFIX + "<red>Animation <white>" + MessageFormats.escape(animation)
                + "</white> paused after an error</red>";
        String hover = MessageFormats.escape(message) + "\n<gray>clear with /billboard resume "
                + MessageFormats.escape(animation) + "</gray>";
        getServer().getConsoleSender().sendMessage(Messages.withHover(line, hover));
        getLogger().severe("Animation \"" + animation + "\" paused after an error: " + message
                + " — clear with /billboard resume " + animation);
    }
}
