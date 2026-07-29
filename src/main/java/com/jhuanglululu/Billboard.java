package com.jhuanglululu;

import com.github.retrooper.packetevents.PacketEvents;
import com.jhuanglululu.billboard.command.BillboardCommand;
import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.config.ConfigLoader;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.load.AnimationLoader;
import com.jhuanglululu.billboard.load.AnimationReloadDiff;
import com.jhuanglululu.billboard.load.BukkitRegistrySource;
import com.jhuanglululu.billboard.load.DataCheck;
import com.jhuanglululu.billboard.load.LoadIssue;
import com.jhuanglululu.billboard.load.ReloadSummary;
import com.jhuanglululu.billboard.message.GuestOutput;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.message.Messages;
import com.jhuanglululu.billboard.placement.BukkitPositionSource;
import com.jhuanglululu.billboard.placement.ProximityController;
import com.jhuanglululu.billboard.placement.ViewerPosition;
import com.jhuanglululu.billboard.render.PaperBlockStateValidator;
import com.jhuanglululu.billboard.render.PaperContentValidator;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.billboard.scheduler.AnimationScheduler;
import com.jhuanglululu.billboard.scheduler.BukkitInstanceLifecycle;
import com.jhuanglululu.billboard.scheduler.RunningInstance;
import com.jhuanglululu.wasmachine.runtime.WorkerPoolSizer;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasm.WasmParseException;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.World;
import org.bukkit.entity.Player;
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
    private Path dataDir;
    private AnimationScheduler scheduler;
    private ProximityController<RunningInstance> controller;
    private GuestOutput guestOutput;
    private long lifecycleTick;
    // Placements load-time validation rejected: they behave as paused until a successful reload.
    private final Set<String> skippedPlacements = new LinkedHashSet<>();

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig();
        loadData();
        guestOutput = new GuestOutput(getServer(), () -> config.logViewers(),
                () -> data.logMuted(), () -> config.consoleLog());
        for (String issue : data.issues()) {
            // Loud, and only now: the data folder is read before the output routing exists.
            guestOutput.issue(MessageFormats.PREFIX + "<red>Skipped unreadable saved data</red>"
                    + " <gray>(hover for details)</gray>",
                    "<red>" + MessageFormats.escape(issue) + "</red>"
                    + "\n<gray>plugins/Billboard/data</gray>");
            getLogger().severe(issue);
        }
        // Validate everything now: every animation and every placement is checked before a single
        // player can be near one, so no failure waits for a proximity trigger.
        validateAll(loadAnimations());

        BlockStateValidator validator = new PaperBlockStateValidator();
        ContentValidator content = new PaperContentValidator();
        WorkerPoolSizer sizer = new WorkerPoolSizer(config.runtime().threads(),
                Runtime.getRuntime().availableProcessors(), config.runtime().poolShrinkDelayTicks());
        scheduler = new AnimationScheduler(this, config.runtime().threads(), sizer,
                () -> config.runtime().instructionBudget(), guestOutput);

        BukkitInstanceLifecycle lifecycle = new BukkitInstanceLifecycle(getServer(), scheduler,
                animations::get, validator, content, () -> config.runtime().memoryCapBytes());
        controller = new ProximityController<>(new BukkitPositionSource(getServer()), lifecycle, data, () -> config);
        controller.setSkippedPlacements(() -> skippedPlacements);
        controller.setPauseHintSink(this::sendPauseHint);

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
                () -> config, this::reload, this::exportRegistry,
                () -> scheduler.pluginStats(data.placements().size()), scheduler);

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
        if (data != null && dataDir != null) {
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
        // Anything unreadable in the file shouts and falls back to its default — the server still
        // boots, but nobody gets to believe a mistyped setting took effect.
        config = ConfigLoader.load(configFile, problem -> {
            String line = MessageFormats.PREFIX + "<red>config.toml: " + MessageFormats.escape(problem)
                    + "</red>";
            getServer().getConsoleSender().sendMessage(Messages.render(line));
            getLogger().severe("config.toml: " + problem);
        });
    }

    /** Scans + validates the animations folder, replacing the loaded modules. */
    private AnimationLoader.Result loadAnimations() {
        AnimationLoader.Result result = AnimationLoader.load(
                getDataFolder().toPath().resolve("animations"));
        animations.clear();
        animations.putAll(result.modules());
        animationHashes.clear();
        animationHashes.putAll(result.hashes());
        return result;
    }

    /**
     * Reports every load-time issue loudly and records which placements are out of service. Runs at
     * startup and after each reload: the animation issues come from {@link AnimationLoader}, the
     * placement issues from cross-checking data.toml against the loaded animations and the server's
     * worlds. This is the primary gate — nothing here is deferred to a proximity trigger.
     */
    private void validateAll(AnimationLoader.Result scan) {
        Set<String> worlds = new LinkedHashSet<>();
        for (World world : getServer().getWorlds()) {
            worlds.add(world.getName());
        }
        List<LoadIssue> issues = new ArrayList<>(scan.issues());
        issues.addAll(DataCheck.check(data.placements(), animations.keySet(), worlds,
                name -> data.existingAnimation(name).orElse(null), Set.copyOf(data.groupIds())));
        skippedPlacements.clear();
        skippedPlacements.addAll(DataCheck.skippedKeys(issues));
        for (LoadIssue issue : issues) {
            guestOutput.issue(issue.line(), issue.hover());
            getLogger().severe(issue.plain() + " \u2014 fix it and run /billboard reload");
        }
    }

    /** Rescan + revalidate the folder, restart changed/removed animations' instances, report. */
    public ReloadSummary reload() {
        Map<String, Integer> before = new HashMap<>(animationHashes);
        AnimationLoader.Result scan = loadAnimations();
        AnimationReloadDiff diff = AnimationReloadDiff.compute(before, scan.hashes());
        for (String name : diff.stopped()) {
            controller.stopInstancesOf(name); // changed/removed: stop (cleanup); proximity restarts it
        }
        // A successful reload rebuilds the skip set from scratch, so a fixed file comes back to life.
        validateAll(scan);
        controller.clearPauseHints(); // reload may change why something is paused: say it again
        saveData();
        List<String> errors = new ArrayList<>();
        for (LoadIssue issue : scan.issues()) {
            errors.add(issue.plain());
        }
        for (String key : skippedPlacements) {
            errors.add("skipped placement " + key);
        }
        getLogger().info("Reloaded animations: +" + diff.added().size() + " ~" + diff.changed().size()
                + " -" + diff.removed().size()
                + (errors.isEmpty() ? "" : " (" + errors.size() + " issue(s))"));
        return new ReloadSummary(diff, errors);
    }

    /** {@code /billboard export registry}: writes the SDK's registry.rs, or null on failure. */
    private int[] exportRegistry() {
        Path target = getDataFolder().toPath().resolve("registry.rs");
        try {
            int[] counts = BukkitRegistrySource.write(target);
            getLogger().info("Exported registry.rs: " + counts[0] + " block(s), "
                    + counts[1] + " item(s)");
            return counts;
        } catch (IOException e) {
            getLogger().severe("Registry export failed: " + e.getMessage());
            return null;
        }
    }

    private void loadData() {
        dataDir = getDataFolder().toPath().resolve("data");
        data = DataStore.load(dataDir);
        // Unreadable records are reported (loudly) once guestOutput exists, not thrown: a corrupt
        // saved record must not stop the server from booting.
        // No lazy pausing here either: validateAll decides what is out of service, per placement,
        // and the paused flag keeps meaning only what it says — an animation an error stopped.
    }

    private void saveData() {
        data.save(dataDir);
    }

    /**
     * Tells one player standing near a paused placement why nothing is there, once. Only admins
     * and config log-viewers hear it — everyone else has no command to act on it with — and the
     * {@code false} return keeps the controller from marking it delivered to the rest.
     */
    private boolean sendPauseHint(Placement placement, ViewerPosition viewer, boolean animationLevel) {
        Player player = getServer().getPlayer(viewer.uuid());
        if (player == null) {
            return false;
        }
        if (!player.hasPermission(BillboardCommand.PERMISSION)
                && !config.logViewers().contains(player.getName())) {
            return false;
        }
        player.sendMessage(Messages.withHover(
                MessageFormats.pauseHint(placement.animation(), placement.id()),
                MessageFormats.pauseHintDetail(placement.animation(), placement.id(), animationLevel)));
        return true;
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
