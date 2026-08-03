package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.data.AnimationSettings;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.Env;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import com.jhuanglululu.billboard.load.ReloadSummary;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.message.Messages;
import com.jhuanglululu.billboard.stats.CaptureControl;
import com.jhuanglululu.billboard.stats.CaptureOrchestrator;
import com.jhuanglululu.billboard.stats.CaptureReport;
import com.jhuanglululu.billboard.stats.PluginStats;
import com.jhuanglululu.billboard.stats.StatsFormats;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The {@code /billboard} Brigadier command tree (spawn/remove/pause/resume/list/whitelist/
 * blacklist/group/set), following the design's rule that literal subcommands precede fields. Handlers
 * mutate the {@link DataStore} (persisted via the save callback); the proximity controller
 * reacts on its next check, so commands need no direct instance handles.
 */
public final class BillboardCommand {

    private final DataStore data;
    private final Runnable save;
    private final Supplier<Set<String>> animationNames;
    private final Server server;
    private final Supplier<BillboardConfig> config;
    private final Supplier<ReloadSummary> reload;
    private final Supplier<int[]> exportRegistry;
    private final Supplier<PluginStats> pluginStats;
    private final CaptureControl captures;
    private final ToIntFunction<EnvTarget> restart;

    private BillboardCommand(DataStore data, Runnable save, Supplier<Set<String>> animationNames,
            Server server, Supplier<BillboardConfig> config, Supplier<ReloadSummary> reload,
            Supplier<int[]> exportRegistry, Supplier<PluginStats> pluginStats,
            CaptureControl captures, ToIntFunction<EnvTarget> restart) {
        this.data = data;
        this.save = save;
        this.animationNames = animationNames;
        this.server = server;
        this.config = config;
        this.reload = reload;
        this.exportRegistry = exportRegistry;
        this.pluginStats = pluginStats;
        this.captures = captures;
        this.restart = restart;
    }

    /**
     * Register {@code /billboard} on the plugin's command lifecycle.
     *
     * @param restart stops every running instance of a resolved target and returns how many it
     *                stopped; the proximity pass starts them again, which is what makes an env
     *                change visible to a guest that can only read environ at start-up
     */
    public static void register(JavaPlugin plugin, DataStore data, Runnable save,
            Supplier<Set<String>> animationNames, Server server, Supplier<BillboardConfig> config,
            Supplier<ReloadSummary> reload, Supplier<int[]> exportRegistry,
            Supplier<PluginStats> pluginStats, CaptureControl captures,
            ToIntFunction<EnvTarget> restart) {
        BillboardCommand cmd = new BillboardCommand(data, save, animationNames, server, config,
                reload, exportRegistry, pluginStats, captures, restart);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(cmd.build(), "Billboard animation control", List.of("bb")));
    }

    /** Permission gating the admin subcommands (default op). */
    public static final String PERMISSION = "billboard.admin";

    /**
     * Permission gating {@code /billboard stats} on its own — a diagnostic role, deliberately not
     * the log-viewer one: reading performance numbers is not the same job as watching guest output.
     * Admins hold it through the umbrella below, so nobody has to be granted both.
     */
    public static final String PERMISSION_STATS = "billboard.stats";

    // Access matrix (see build()): admins get every subcommand; config log-viewers get ONLY
    // /billboard log; billboard.stats holders get ONLY /billboard stats; everyone else sees
    // nothing (root .requires fails).
    private boolean isAdmin(CommandSourceStack src) {
        return src.getSender().hasPermission(PERMISSION);
    }

    private boolean isLogViewer(CommandSourceStack src) {
        return config.get().logViewers().contains(src.getSender().getName());
    }

    private boolean isStatsUser(CommandSourceStack src) {
        return isAdmin(src) || src.getSender().hasPermission(PERMISSION_STATS);
    }

    private boolean rootAccess(CommandSourceStack src) {
        return isAdmin(src) || isLogViewer(src) || isStatsUser(src);
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        // Root is reachable by admins OR config log-viewers OR billboard.stats holders; each
        // admin subcommand re-checks admin, /billboard log checks log-viewer-or-admin and
        // /billboard stats checks stats-or-admin — so each non-admin role reaches exactly its
        // own subcommand, and someone with none of the three reaches nothing.
        return Commands.literal("billboard")
                .requires(this::rootAccess)
                .then(spawn())
                .then(remove())
                .then(pause())
                .then(resume())
                .then(reload())
                .then(export())
                .then(list())
                .then(listFilter("whitelist"))
                .then(listFilter("blacklist"))
                .then(group())
                .then(set())
                .then(env())
                .then(restart())
                .then(log())
                .then(stats())
                .build();
    }

    // --- spawn <animation> <id> <x y z> <visibility> [yaw] [pitch] [roll] ---

    /**
     * The three rotation arguments trail the command as a nested optional chain: {@code visibility}
     * sits right after the coordinates, so its word suggestions appear at a fixed position, and
     * each rotation depth carries its own {@code executes} so the command is complete after any
     * prefix of {@code yaw pitch roll}.
     *
     * <p><b>{@code type} is gone from the grammar</b> as of ABI 4: it is now an env key like any
     * other, set with {@code /billboard env <target> set type per_player} (or, for a whole
     * animation's placements at once, the older {@code /billboard set <animation> type …}). A
     * placement that never says otherwise is {@code shared}, which is the common case and no longer
     * has to be typed.
     */
    private LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return Commands.literal("spawn").requires(this::isAdmin).then(
            Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
            Commands.argument("id", StringArgumentType.word()).then(
            Commands.argument("x", CoordinateArgument.INSTANCE).suggests(coordinateSuggestions(Axis.X)).then(
            Commands.argument("y", CoordinateArgument.INSTANCE).suggests(coordinateSuggestions(Axis.Y)).then(
            Commands.argument("z", CoordinateArgument.INSTANCE).suggests(coordinateSuggestions(Axis.Z)).then(
            Commands.argument("visibility", StringArgumentType.word())
                    .suggests(literals("everyone", "none", "whitelist", "blacklist"))
                    .executes(this::doSpawn)
                    // The rotation is a trailing option: visibility sits before it, so its word
                    // suggestions appear at a fixed position instead of after a variable number
                    // of angles.
                    .then(rotationArgument("yaw").executes(this::doSpawn)
                            .then(rotationArgument("pitch").executes(this::doSpawn)
                                    .then(rotationArgument("roll")
                                            .executes(this::doSpawn))))))))));
    }

    /** One rotation argument: degrees, any value — Minecraft angles wrap, so nothing is invalid. */
    private static RequiredArgumentBuilder<CommandSourceStack, Double> rotationArgument(String name) {
        return Commands.argument(name, DoubleArgumentType.doubleArg())
                .suggests(literals("0", "45", "90", "180", "270"));
    }

    private int doSpawn(CommandContext<CommandSourceStack> ctx) {
        String animation = StringArgumentType.getString(ctx, "animation");
        String id = StringArgumentType.getString(ctx, "id");
        String world = worldOf(ctx);
        Map<Axis, Coordinate> coordinates = new EnumMap<>(Axis.class);
        for (Axis axis : Axis.values()) {
            String token = StringArgumentType.getString(ctx, axis.argument());
            try {
                coordinates.put(axis, Coordinate.parse(token));
            } catch (IllegalArgumentException e) {
                reply(ctx, SpawnValidator.badCoordinate(axis.argument(), token));
                return Command.SINGLE_SUCCESS;
            }
        }
        // A relative coordinate is measured from the sender's own position, so a sender without
        // one is refused outright rather than silently measured from somewhere else.
        Player player = ctx.getSource().getSender() instanceof Player p ? p : null;
        if (player == null && coordinates.values().stream().anyMatch(Coordinate::relative)) {
            reply(ctx, SpawnValidator.relativeNeedsPlayer());
            return Command.SINGLE_SUCCESS;
        }
        double x = coordinates.get(Axis.X).resolve(player == null ? 0 : Axis.X.of(player));
        double y = coordinates.get(Axis.Y).resolve(player == null ? 0 : Axis.Y.of(player));
        double z = coordinates.get(Axis.Z).resolve(player == null ? 0 : Axis.Z.of(player));
        double yaw = optionalDouble(ctx, "yaw");
        double pitch = optionalDouble(ctx, "pitch");
        double roll = optionalDouble(ctx, "roll");
        Optional<String> unknown = SpawnValidator.rejectUnknown(animation, animationNames.get());
        if (unknown.isPresent()) {
            reply(ctx, unknown.get());
            return Command.SINGLE_SUCCESS;
        }
        String visibilityToken = StringArgumentType.getString(ctx, "visibility");
        VisibilityMode visibility;
        try {
            visibility = VisibilityMode.fromWire(visibilityToken);
        } catch (IllegalArgumentException e) {
            reply(ctx, unknownToken("visibility mode", visibilityToken));
            return Command.SINGLE_SUCCESS;
        }
        // A fresh placement carries no env at all — not even an explicit "type": absent already
        // means shared, and writing it out would make every new line carry a key nobody chose.
        data.putPlacement(new Placement(animation, id, world, x, y, z, yaw, pitch, roll,
                Map.of(), visibility));
        save.run();
        // An unrotated placement reads exactly as it always did; the rotation is appended only
        // when there is one, so the common line does not grow three zeroes.
        String rotation = yaw == 0 && pitch == 0 && roll == 0
                ? ""
                : " facing <white>" + fmt(yaw) + ", " + fmt(pitch) + ", " + fmt(roll) + "</white>";
        reply(ctx, MessageFormats.PREFIX + "<green>Placed <white>" + esc(animation) + "/" + esc(id)
                + "</white> at <white>" + fmt(x) + ", " + fmt(y) + ", " + fmt(z)
                + "</white>" + rotation + " in <white>" + esc(world) + "</white></green>");
        return Command.SINGLE_SUCCESS;
    }

    /** {@code Unknown <what>: <token>} — the one shape every rejected enum token gets. */
    private static String unknownToken(String what, String token) {
        return MessageFormats.PREFIX + "<red>Unknown " + what + ": <white>" + esc(token)
                + "</white></red>";
    }

    // --- remove <animation> <id> ---

    private LiteralArgumentBuilder<CommandSourceStack> remove() {
        return Commands.literal("remove").requires(this::isAdmin).then(
            Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
            Commands.argument("id", StringArgumentType.word()).suggests(placementIdSuggestions())
                    .executes(ctx -> {
                        String animation = StringArgumentType.getString(ctx, "animation");
                        String id = StringArgumentType.getString(ctx, "id");
                        if (data.removePlacement(animation, id).isPresent()) {
                            save.run();
                            reply(ctx, MessageFormats.PREFIX + "<green>Removed <white>" + esc(animation)
                                    + "/" + esc(id) + "</white></green>");
                        } else {
                            reply(ctx, noSuchPlacement(animation, id));
                        }
                        return Command.SINGLE_SUCCESS;
                    })));
    }

    /** The one shape {@code remove} and the filter commands share for an id that names nothing. */
    private static String noSuchPlacement(String animation, String id) {
        return MessageFormats.PREFIX + "<red>No such placement <white>" + esc(animation) + "/"
                + esc(id) + "</white></red>";
    }

    // --- pause|resume <animation | placement-id> ---

    /**
     * {@code /billboard pause <animation|placement-id>} — deliberately disable something that
     * works. The animation form sets the very flag an error sets (one concept, one switch); the
     * placement form sets the per-placement flag, so the animation's other placements keep running.
     */
    private LiteralArgumentBuilder<CommandSourceStack> pause() {
        return Commands.literal("pause").requires(this::isAdmin).then(
            Commands.argument("target", StringArgumentType.word()).suggests(pauseTargetSuggestions())
                    .executes(ctx -> setPaused(ctx, true)));
    }

    /** {@code /billboard resume <animation|placement-id>} — clears whichever flag the word names. */
    private LiteralArgumentBuilder<CommandSourceStack> resume() {
        return Commands.literal("resume").requires(this::isAdmin).then(
            Commands.argument("target", StringArgumentType.word()).suggests(pauseTargetSuggestions())
                    .executes(ctx -> setPaused(ctx, false)));
    }

    private int setPaused(CommandContext<CommandSourceStack> ctx, boolean paused) {
        String target = StringArgumentType.getString(ctx, "target");
        PauseTarget resolved = PauseTarget.resolve(target, knownAnimations(), data.placements());
        String verb = paused ? "Paused" : "Resumed";
        switch (resolved.kind()) {
            case ANIMATION -> {
                data.animation(resolved.animation()).setPaused(paused);
                save.run();
                reply(ctx, MessageFormats.PREFIX + "<green>" + verb + " animation <white>"
                        + esc(resolved.animation()) + "</white>"
                        + (paused ? "" : " (error-pause cleared)") + "</green>");
            }
            case PLACEMENT -> {
                Placement p = data.placement(resolved.animation(), resolved.id()).orElseThrow();
                data.putPlacement(p.withPaused(paused));
                save.run();
                reply(ctx, MessageFormats.PREFIX + "<green>" + verb + " placement <white>"
                        + esc(p.key()) + "</white></green>");
            }
            case AMBIGUOUS -> replyAmbiguous(ctx, resolved);
            case UNKNOWN -> reply(ctx, noSuchTarget(target));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The one error shape a placement id shared by several animations gets, wherever it is
     * resolved — pause, resume or stats all send this, so the fix reads the same everywhere.
     */
    private void replyAmbiguous(CommandContext<CommandSourceStack> ctx, PauseTarget resolved) {
        String line = MessageFormats.PREFIX + "<red>Placement id <white>" + esc(resolved.id())
                + "</white> is used by <white>" + resolved.candidates().size()
                + "</white> animations</red>";
        StringBuilder hover = new StringBuilder("<gray>name the animation instead, or one of:</gray>");
        for (String key : resolved.candidates()) {
            hover.append("\n<white>").append(esc(key)).append("</white>");
        }
        ctx.getSource().getSender().sendMessage(Messages.withHover(line, hover.toString()));
    }

    /** The one shape a word that resolves to neither an animation nor a placement gets. */
    private static String noSuchTarget(String target) {
        return MessageFormats.PREFIX + "<red>No animation or placement named <white>" + esc(target)
                + "</white></red>";
    }

    /**
     * Every animation a pause/resume may name: the loaded modules plus any with persisted settings
     * — an animation whose file broke keeps its paused flag and must stay resumable.
     */
    private Set<String> knownAnimations() {
        Set<String> names = new LinkedHashSet<>(animationNames.get());
        names.addAll(data.animationNames());
        return names;
    }

    // --- reload ---

    private LiteralArgumentBuilder<CommandSourceStack> reload() {
        return Commands.literal("reload").requires(this::isAdmin).executes(ctx -> {
            ReloadSummary s = reload.get();
            String line = MessageFormats.PREFIX + "<green>reloaded animations: <white>+"
                    + s.diff().added().size() + " ~" + s.diff().changed().size() + " -"
                    + s.diff().removed().size() + "</white></green>"
                    + (s.errors().isEmpty() ? "" : " <red>(" + s.errors().size() + " error(s))</red>");
            StringBuilder hover = new StringBuilder();
            hover.append("added: <gray>").append(esc(String.valueOf(s.diff().added())))
                    .append("</gray>\nchanged: <gray>").append(esc(String.valueOf(s.diff().changed())))
                    .append("</gray>\nremoved: <gray>").append(esc(String.valueOf(s.diff().removed())))
                    .append("</gray>");
            for (String err : s.errors()) {
                hover.append("\n<red>").append(esc(err)).append("</red>");
            }
            ctx.getSource().getSender().sendMessage(Messages.withHover(line, hover.toString()));
            return Command.SINGLE_SUCCESS;
        });
    }

    // --- export registry ---

    /**
     * {@code /billboard export registry} — literal subcommands before any field, per the design's
     * command-tree rule, so {@code export} can grow other targets later without moving anything.
     */
    private LiteralArgumentBuilder<CommandSourceStack> export() {
        return Commands.literal("export").requires(this::isAdmin)
                .then(Commands.literal("registry").executes(this::doExportRegistry));
    }

    private int doExportRegistry(CommandContext<CommandSourceStack> ctx) {
        int[] counts = exportRegistry.get();
        if (counts == null) {
            Messages.send(ctx.getSource().getSender(), MessageFormats.PREFIX
                    + "<red>Registry export failed — see the server log</red>");
            return 0;
        }
        String line = MessageFormats.PREFIX + "<green>Exported <white>registry.rs</white> — <white>"
                + counts[0] + "</white> block(s), <white>" + counts[1] + "</white> item(s)</green>";
        String hover = "<white>plugins/Billboard/registry.rs</white>"
                + "\n<green>point <white>$BILLBOARD_REGISTRY</white> at it and rebuild the animation</green>";
        ctx.getSource().getSender().sendMessage(Messages.withHover(line, hover));
        return Command.SINGLE_SUCCESS;
    }

    // --- log <on|off> (per-player mute for log-viewers) ---

    private LiteralArgumentBuilder<CommandSourceStack> log() {
        return Commands.literal("log")
                .requires(this::rootAccess)
                .then(Commands.literal("on").executes(ctx -> setMuted(ctx, false)))
                .then(Commands.literal("off").executes(ctx -> setMuted(ctx, true)));
    }

    private int setMuted(CommandContext<CommandSourceStack> ctx, boolean mute) {
        String name = ctx.getSource().getSender().getName();
        if (!config.get().logViewers().contains(name)) {
            reply(ctx, MessageFormats.PREFIX + "<red>You are not a log viewer, so there is nothing to "
                    + (mute ? "mute" : "unmute") + "</red>");
            return Command.SINGLE_SUCCESS;
        }
        if (mute) {
            data.logMuted().add(name);
        } else {
            data.logMuted().remove(name);
        }
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>Guest log output is now "
                + (mute ? "muted" : "unmuted") + " for you</green>");
        return Command.SINGLE_SUCCESS;
    }

    // --- stats [animation|placement-id [seconds]] ---

    /** The default capture window, in seconds, when the command is given no length. */
    private static final int DEFAULT_CAPTURE_SECONDS = 10;

    /** The longest capture the command accepts: ten minutes, per the plan. */
    private static final int MAX_CAPTURE_SECONDS = 600;

    /**
     * {@code /billboard stats} — bare, the instant plugin-wide view (no capture, no waiting); with
     * a target, a capture window whose report arrives when it closes.
     */
    private LiteralArgumentBuilder<CommandSourceStack> stats() {
        return Commands.literal("stats")
                .requires(this::isStatsUser)
                .executes(ctx -> {
                    StatsFormats.Line line = StatsFormats.pluginSummary(pluginStats.get());
                    ctx.getSource().getSender().sendMessage(
                            Messages.withHover(line.visible(), line.hover()));
                    return Command.SINGLE_SUCCESS;
                })
                // Literal before field, per the tree rule — which does mean an animation
                // literally named "stop" cannot be targeted here. Renaming the file is a cheaper
                // fix than a grammar that guesses.
                .then(Commands.literal("stop").then(
                        Commands.argument("target", StringArgumentType.word())
                                .suggests(pauseTargetSuggestions())
                                .executes(this::stopCapture)))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(pauseTargetSuggestions())
                        .executes(ctx -> startCapture(ctx, DEFAULT_CAPTURE_SECONDS))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer())
                                .suggests(literals("10", "30", "60"))
                                .executes(ctx -> startCapture(ctx,
                                        IntegerArgumentType.getInteger(ctx, "seconds")))));
    }

    private int startCapture(CommandContext<CommandSourceStack> ctx, int seconds) {
        String target = StringArgumentType.getString(ctx, "target");
        if (seconds < 1 || seconds > MAX_CAPTURE_SECONDS) {
            reply(ctx, MessageFormats.PREFIX + "<red>Capture length must be <white>1.."
                    + MAX_CAPTURE_SECONDS + "</white> seconds, got <white>" + seconds
                    + "</white></red>");
            return Command.SINGLE_SUCCESS;
        }
        PauseTarget resolved = PauseTarget.resolve(target, knownAnimations(), data.placements());
        if (!resolveForCapture(ctx, target, resolved)) {
            return Command.SINGLE_SUCCESS;
        }
        // An animation covers all its placements; a placement id narrows to the one.
        String animation = resolved.animation();
        String placementId = resolved.kind() == PauseTarget.Kind.PLACEMENT ? resolved.id() : null;

        int windowTicks = seconds * StatsFormats.TICKS_PER_SECOND;
        CaptureOrchestrator.CaptureStart start = captures.startCapture(target, animation,
                placementId, windowTicks, reportTo(ctx));
        if (!start.started()) {
            reply(ctx, StatsFormats.captureAlreadyRunning(target, start.remainingTicks()));
            return Command.SINGLE_SUCCESS;
        }
        reply(ctx, StatsFormats.captureStarted(target, seconds, start.armed()));
        if (start.armed() == 0) {
            // Loud, immediately: otherwise the user waits out the whole window for a report that
            // can only say "nothing ran".
            reply(ctx, StatsFormats.noInstances(target));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /billboard stats stop <target>} — end the window now and report what it saw. The
     * report goes to whoever started the capture, so the {@code [stop]} on their own capturing
     * line lands back with them.
     */
    private int stopCapture(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        PauseTarget resolved = PauseTarget.resolve(target, knownAnimations(), data.placements());
        if (!resolveForCapture(ctx, target, resolved)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!captures.stopCapture(target)) {
            reply(ctx, StatsFormats.noCaptureRunning(target));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Reports the resolution failures {@code stats} and {@code stats stop} share.
     *
     * @return whether the word resolved to something capturable
     */
    private boolean resolveForCapture(CommandContext<CommandSourceStack> ctx, String target,
            PauseTarget resolved) {
        if (resolved.kind() == PauseTarget.Kind.AMBIGUOUS) {
            replyAmbiguous(ctx, resolved);
            return false;
        }
        if (resolved.kind() == PauseTarget.Kind.UNKNOWN) {
            reply(ctx, noSuchTarget(target));
            return false;
        }
        return true;
    }

    /**
     * Where a finished report goes. A player is looked up again when the window closes — captures
     * last minutes and people log off — and the console takes it if they are gone.
     */
    private Consumer<CaptureReport> reportTo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        UUID playerId = sender instanceof Player p ? p.getUniqueId() : null;
        return report -> {
            Audience audience = sender;
            if (playerId != null) {
                Player online = server.getPlayer(playerId);
                audience = online != null ? online : server.getConsoleSender();
            }
            sendReport(audience, report);
        };
    }

    private static void sendReport(Audience audience, CaptureReport report) {
        if (!report.anySamples()) {
            Messages.send(audience, StatsFormats.reportWithoutSamples(report));
            return;
        }
        StatsFormats.Line header = StatsFormats.reportHeader(report);
        audience.sendMessage(Messages.withHover(header.visible(), header.hover()));
        for (CaptureReport.MergedInstance instance : report.merged()) {
            StatsFormats.Line line = StatsFormats.instanceLine(instance, report.elapsedTicks());
            if (line.hover() == null) {
                Messages.send(audience, line.visible());
            } else {
                audience.sendMessage(Messages.withHover(line.visible(), line.hover()));
            }
        }
    }

    // --- list [animation] ---

    private LiteralArgumentBuilder<CommandSourceStack> list() {
        return Commands.literal("list")
                .requires(this::isAdmin)
                .executes(ctx -> {
                    listAll(ctx);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions())
                        .executes(ctx -> {
                            listAnimation(ctx, StringArgumentType.getString(ctx, "animation"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void listAll(CommandContext<CommandSourceStack> ctx) {
        reply(ctx, MessageFormats.PREFIX + "<green>placements:</green>");
        for (Placement p : data.placements()) {
            boolean paused = p.paused()
                    || data.existingAnimation(p.animation()).map(AnimationSettings::paused).orElse(false);
            String line = "<white>" + esc(p.key()) + "</white> <gray>(" + p.type().wire() + ", "
                    + p.visibility().wire() + (paused ? ", <red>paused</red>" : "") + ")</gray>";
            String hover = "<white>" + esc(p.world()) + " <gray>at</gray> " + fmt(p.x()) + ", "
                    + fmt(p.y()) + ", " + fmt(p.z()) + "</white>";
            ctx.getSource().getSender().sendMessage(Messages.withHover(line, hover));
        }
    }

    private void listAnimation(CommandContext<CommandSourceStack> ctx, String animation) {
        boolean paused = data.existingAnimation(animation).map(AnimationSettings::paused).orElse(false);
        reply(ctx, MessageFormats.PREFIX + "<white>" + esc(animation) + "</white>"
                + (paused ? " <red>[paused]</red>" : ""));
        for (Placement p : data.placements()) {
            if (!p.animation().equals(animation)) {
                continue;
            }
            String line = "<gray><white>" + esc(p.id()) + "</white> - <white>" + p.type().wire()
                    + "</white>, <white>" + p.visibility().wire() + "</white>"
                    + (p.paused() ? ", <red>paused</red>" : "") + "</gray>";
            ctx.getSource().getSender().sendMessage(Messages.withHover(line, placementHover(p)));
        }
    }

    /**
     * The detail behind one line of {@code /billboard list <animation>}: the list the placement's
     * mode actually consults (omitted entirely when it consults neither), then who can see it now.
     */
    private String placementHover(Placement p) {
        StringBuilder hover = new StringBuilder();
        if (p.visibility() == VisibilityMode.WHITELIST || p.visibility() == VisibilityMode.BLACKLIST) {
            boolean whitelist = p.visibility() == VisibilityMode.WHITELIST;
            hover.append("<white>").append(whitelist ? "whitelist" : "blacklist")
                    .append("</white> <gray>").append(entries(p.filter(whitelist))).append("</gray>\n");
        }
        return hover.append("<white>viewers</white> <gray>").append(eligibleNames(p))
                .append("</gray>").toString();
    }

    private String eligibleNames(Placement p) {
        double radius = config.get().proximity().radius();
        StringBuilder sb = new StringBuilder();
        for (Player pl : server.getOnlinePlayers()) {
            var loc = pl.getLocation();
            String world = loc.getWorld() == null ? "" : loc.getWorld().getName();
            boolean inRange = world.equals(p.world())
                    && sq(loc.getX() - p.x()) + sq(loc.getY() - p.y()) + sq(loc.getZ() - p.z()) <= radius * radius;
            boolean visible = com.jhuanglululu.billboard.placement.Eligibility.visibleTo(
                    p.visibility(), p.whitelist(), p.blacklist(), data.groupsView(), pl.getName());
            if (inRange && visible) {
                sb.append(sb.isEmpty() ? "" : ", ").append(esc(pl.getName()));
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    // --- whitelist|blacklist add|remove <animation> <id> <entry> | list <animation> <id> ---

    /**
     * The two visibility lists are per placement, so both forms address one the way spawn and
     * remove do — {@code <animation> <id>} — rather than an animation alone.
     */
    private LiteralArgumentBuilder<CommandSourceStack> listFilter(String which) {
        boolean whitelist = which.equals("whitelist");
        return Commands.literal(which)
                .requires(this::isAdmin)
                .then(Commands.literal("add").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
                    Commands.argument("id", StringArgumentType.word()).suggests(placementIdSuggestions()).then(
                    Commands.argument("entry", StringArgumentType.word()).suggests(playerOrGroupSuggestions())
                            .executes(ctx -> editFilter(ctx, whitelist, true))))))
                .then(Commands.literal("remove").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
                    Commands.argument("id", StringArgumentType.word()).suggests(placementIdSuggestions()).then(
                    Commands.argument("entry", StringArgumentType.word()).suggests(filterEntrySuggestions(whitelist))
                            .executes(ctx -> editFilter(ctx, whitelist, false))))))
                .then(Commands.literal("list").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
                    Commands.argument("id", StringArgumentType.word()).suggests(placementIdSuggestions())
                            .executes(ctx -> {
                                String animation = StringArgumentType.getString(ctx, "animation");
                                String id = StringArgumentType.getString(ctx, "id");
                                Optional<Placement> p = data.placement(animation, id);
                                if (p.isEmpty()) {
                                    reply(ctx, noSuchPlacement(animation, id));
                                    return Command.SINGLE_SUCCESS;
                                }
                                reply(ctx, MessageFormats.PREFIX + "<white>" + esc(animation) + "/"
                                        + esc(id) + "</white> <green>" + which + ":</green> <gray>"
                                        + entries(p.get().filter(whitelist)) + "</gray>");
                                return Command.SINGLE_SUCCESS;
                            }))));
    }

    private int editFilter(CommandContext<CommandSourceStack> ctx, boolean whitelist, boolean add) {
        String animation = StringArgumentType.getString(ctx, "animation");
        String id = StringArgumentType.getString(ctx, "id");
        String entry = StringArgumentType.getString(ctx, "entry");
        Optional<Placement> found = data.placement(animation, id);
        if (found.isEmpty()) {
            reply(ctx, noSuchPlacement(animation, id));
            return Command.SINGLE_SUCCESS;
        }
        Placement p = found.get();
        Set<String> updated = new LinkedHashSet<>(p.filter(whitelist));
        if (add) {
            updated.add(entry);
        } else {
            updated.remove(entry);
        }
        data.putPlacement(p.withFilter(whitelist, updated));
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>" + (add ? "Added" : "Removed") + " <white>"
                + esc(entry) + "</white> " + (add ? "to" : "from") + " "
                + (whitelist ? "whitelist" : "blacklist") + " of <white>" + esc(animation) + "/"
                + esc(id) + "</white></green>");
        return Command.SINGLE_SUCCESS;
    }

    // --- group create|add|remove|list ---

    private LiteralArgumentBuilder<CommandSourceStack> group() {
        return Commands.literal("group")
                .requires(this::isAdmin)
                .then(Commands.literal("create").then(
                    Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "id");
                        data.group(id);
                        save.run();
                        reply(ctx, MessageFormats.PREFIX + "<green>Created group <white>" + esc(id)
                                + "</white></green>");
                        return Command.SINGLE_SUCCESS;
                    })))
                .then(Commands.literal("add").then(
                    Commands.argument("id", StringArgumentType.word()).suggests(groupSuggestions()).then(
                    Commands.argument("player", StringArgumentType.word()).suggests(playerSuggestions())
                            .executes(ctx -> groupEdit(ctx, true)))))
                .then(Commands.literal("remove").then(
                    Commands.argument("id", StringArgumentType.word()).suggests(groupSuggestions()).then(
                    Commands.argument("player", StringArgumentType.word())
                            .executes(ctx -> groupEdit(ctx, false)))))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        reply(ctx, MessageFormats.PREFIX + "<green>groups:</green> <gray>"
                                + entries(data.groupIds()) + "</gray>");
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(groupSuggestions())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                reply(ctx, MessageFormats.PREFIX + "<white>" + esc(id) + "</white>: <gray>"
                                        + entries(data.group(id)) + "</gray>");
                                return Command.SINGLE_SUCCESS;
                            })));
    }

    private int groupEdit(CommandContext<CommandSourceStack> ctx, boolean add) {
        String id = StringArgumentType.getString(ctx, "id");
        String player = StringArgumentType.getString(ctx, "player");
        if (add) {
            data.group(id).add(player);
        } else {
            data.group(id).remove(player);
        }
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>" + (add ? "Added" : "Removed") + " <white>"
                + esc(player) + "</white> " + (add ? "to" : "from") + " group <white>" + esc(id)
                + "</white></green>");
        return Command.SINGLE_SUCCESS;
    }

    // --- set <animation> <field> <value> ---

    private LiteralArgumentBuilder<CommandSourceStack> set() {
        return Commands.literal("set").requires(this::isAdmin).then(
            Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
            Commands.argument("field", StringArgumentType.word()).suggests(literals("visibility", "type")).then(
            Commands.argument("value", StringArgumentType.word()).executes(this::doSet))));
    }

    private int doSet(CommandContext<CommandSourceStack> ctx) {
        String animation = StringArgumentType.getString(ctx, "animation");
        String field = StringArgumentType.getString(ctx, "field");
        String value = StringArgumentType.getString(ctx, "value");
        // The field and its value are validated up front, before any placement is touched, so the
        // message can name the one rejected word — and so a bad value changes nothing at all.
        if (!field.equals("visibility") && !field.equals("type")) {
            reply(ctx, MessageFormats.PREFIX + "<red>Unknown field: <white>" + esc(field)
                    + "</white></red>");
            return Command.SINGLE_SUCCESS;
        }
        VisibilityMode visibility = null;
        InstanceType type = null;
        try {
            if (field.equals("visibility")) {
                visibility = VisibilityMode.fromWire(value);
            } else {
                type = InstanceType.fromWire(value);
            }
        } catch (IllegalArgumentException e) {
            reply(ctx, unknownToken(field.equals("visibility") ? "visibility mode" : "instance type", value));
            return Command.SINGLE_SUCCESS;
        }
        int changed = 0;
        for (Placement p : List.copyOf(data.placements())) {
            if (!p.animation().equals(animation)) {
                continue;
            }
            data.putPlacement(visibility != null ? p.withVisibility(visibility) : p.withType(type));
            changed++;
        }
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>Set <white>" + esc(field) + "=" + esc(value)
                + "</white> on <white>" + changed + "</white> placement(s) of <white>"
                + esc(animation) + "</white></green>");
        if (type != null) {
            // type is an env key now, and env is fixed for a run — so the same restart
            // /billboard env performs is owed here too. (It is also what moves a running
            // instance between the controller's shared and per-player tracking.)
            reportRestart(ctx, new EnvTarget(EnvTarget.Kind.ANIMATION, animation, null));
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- env <target> set <key> <value> | unset <key> | list ---

    /**
     * {@code /billboard env <animation|animation/id> set|unset|list …}.
     *
     * <p>The target comes before the literal here, against the tree's usual rule, because the two
     * env layers <em>are</em> the subject: {@code env demo set …} and {@code env demo/lobby set …}
     * are the same verb on different things, and hoisting {@code set} would force the layer to be
     * spelled some other way. {@link EnvTarget} is what tells them apart, on the slash.
     *
     * <p>{@code value} is a greedy string, so an env value may contain spaces — these are opaque
     * strings the host never parses, and a MiniMessage line or a sentence is a perfectly reasonable
     * thing to hand an animation.
     */
    private LiteralArgumentBuilder<CommandSourceStack> env() {
        return Commands.literal("env").requires(this::isAdmin).then(
            Commands.argument("target", StringArgumentType.word()).suggests(envTargetSuggestions())
                .then(Commands.literal("set").then(
                    Commands.argument("key", StringArgumentType.word()).suggests(envKeySuggestions()).then(
                    Commands.argument("value", StringArgumentType.greedyString())
                            .executes(this::doEnvSet))))
                .then(Commands.literal("unset").then(
                    Commands.argument("key", StringArgumentType.word()).suggests(envKeySuggestions())
                            .executes(this::doEnvUnset)))
                .then(Commands.literal("list").executes(this::doEnvList)));
    }

    private int doEnvSet(CommandContext<CommandSourceStack> ctx) {
        EnvTarget target = resolveEnvTarget(ctx);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        // Both checks run before anything is written, so a rejected key or value changes nothing
        // at all and restarts nothing.
        Optional<String> badKey = Env.rejectKey(key);
        if (badKey.isPresent()) {
            reply(ctx, MessageFormats.PREFIX + "<red>Cannot set <white>" + esc(key) + "</white>: "
                    + esc(badKey.get()) + "</red>");
            return Command.SINGLE_SUCCESS;
        }
        Optional<String> badValue = Env.rejectValue(key, value);
        if (badValue.isPresent()) {
            reply(ctx, unknownToken(badValue.get(), value));
            return Command.SINGLE_SUCCESS;
        }
        if (target.kind() == EnvTarget.Kind.ANIMATION) {
            data.animation(target.animation()).env().put(key, value);
        } else {
            Placement p = data.placement(target.animation(), target.id()).orElseThrow();
            data.putPlacement(p.withEnvEntry(key, value));
        }
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>Set <white>" + esc(key) + "=" + esc(value)
                + "</white> on <white>" + esc(target.label()) + "</white></green>");
        reportRestart(ctx, target);
        return Command.SINGLE_SUCCESS;
    }

    private int doEnvUnset(CommandContext<CommandSourceStack> ctx) {
        EnvTarget target = resolveEnvTarget(ctx);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        String key = StringArgumentType.getString(ctx, "key");
        boolean removed;
        if (target.kind() == EnvTarget.Kind.ANIMATION) {
            removed = data.animation(target.animation()).env().remove(key) != null;
        } else {
            Placement p = data.placement(target.animation(), target.id()).orElseThrow();
            Map<String, String> updated = new LinkedHashMap<>(p.env());
            removed = updated.remove(key) != null;
            data.putPlacement(p.withEnv(updated));
        }
        if (!removed) {
            // Nothing changed, so nothing is restarted — and saying so beats a green line that
            // claims a key was removed from a layer that never had it.
            reply(ctx, MessageFormats.PREFIX + "<red>No <white>" + esc(key)
                    + "</white> in the env of <white>" + esc(target.label()) + "</white></red>");
            return Command.SINGLE_SUCCESS;
        }
        save.run();
        reply(ctx, MessageFormats.PREFIX + "<green>Unset <white>" + esc(key)
                + "</white> on <white>" + esc(target.label()) + "</white></green>");
        reportRestart(ctx, target);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code env list}. On an animation this is just its own layer; on a placement it is the
     * <em>merged</em> view every instance of it will actually see, each key tagged with the layer
     * it came from — which is the only way to answer "why is this value not what I set".
     */
    private int doEnvList(CommandContext<CommandSourceStack> ctx) {
        EnvTarget target = resolveEnvTarget(ctx);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (target.kind() == EnvTarget.Kind.ANIMATION) {
            Map<String, String> layer = data.existingAnimation(target.animation())
                    .map(AnimationSettings::env).orElse(Map.of());
            reply(ctx, envHeader(target, layer.isEmpty()));
            layer.forEach((key, value) -> reply(ctx, envLine(key, value, "animation")));
            return Command.SINGLE_SUCCESS;
        }
        Placement p = data.placement(target.animation(), target.id()).orElseThrow();
        Map<String, String> animationLayer = data.existingAnimation(target.animation())
                .map(AnimationSettings::env).orElse(Map.of());
        // The owner is unknown until an instance actually starts, so bb.player is described
        // rather than invented; every other built-in is fully determined by the placement.
        Map<String, String> merged = Env.merge(animationLayer, p.env());
        merged.putAll(Env.builtins(p, null));
        reply(ctx, envHeader(target, merged.isEmpty()));
        merged.forEach((key, value) -> reply(ctx, envLine(key, value,
                sourceOf(key, animationLayer, p.env()))));
        if (p.type() == InstanceType.PER_PLAYER) {
            reply(ctx, "<gray>" + esc(Env.PREFIX + "player") + " is added per instance (the owner)</gray>");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Which layer a merged key's winning value came from. */
    private static String sourceOf(String key, Map<String, String> animation,
            Map<String, String> placement) {
        if (key.startsWith(Env.PREFIX)) {
            return "built-in";
        }
        if (placement.containsKey(key)) {
            return "placement";
        }
        return animation.containsKey(key) ? "animation" : "built-in";
    }

    private static String envHeader(EnvTarget target, boolean empty) {
        return MessageFormats.PREFIX + "<white>" + esc(target.label()) + "</white> <green>env:</green>"
                + (empty ? " <gray>(none)</gray>" : "");
    }

    private static String envLine(String key, String value, String source) {
        return "<white>" + esc(key) + "</white><gray>=</gray><white>" + esc(value)
                + "</white> <gray>(" + source + ")</gray>";
    }

    /**
     * Resolves the {@code target} argument, replying and returning {@code null} when it names
     * nothing. Shared by the three env verbs and by {@code restart}.
     */
    private EnvTarget resolveEnvTarget(CommandContext<CommandSourceStack> ctx) {
        String token = StringArgumentType.getString(ctx, "target");
        EnvTarget target = EnvTarget.resolve(token, knownAnimations(), data.placements());
        if (target.kind() == EnvTarget.Kind.UNKNOWN) {
            reply(ctx, noSuchTarget(token));
            return null;
        }
        return target;
    }

    /** Restarts the target's instances and says how many, so a silent no-op is never a mystery. */
    private void reportRestart(CommandContext<CommandSourceStack> ctx, EnvTarget target) {
        int stopped = restart.applyAsInt(target);
        reply(ctx, MessageFormats.PREFIX + "<green>Restarted <white>" + stopped
                + "</white> instance(s) of <white>" + esc(target.label()) + "</white></green>");
    }

    // --- restart <animation | animation/id> ---

    /**
     * {@code /billboard restart <target>} — the same stop-and-let-proximity-restart an env change
     * performs, without an env change. Useful on its own: it is the only way to make a running
     * instance start over from its first tick without editing something.
     */
    private LiteralArgumentBuilder<CommandSourceStack> restart() {
        return Commands.literal("restart").requires(this::isAdmin).then(
            Commands.argument("target", StringArgumentType.word()).suggests(envTargetSuggestions())
                    .executes(ctx -> {
                        EnvTarget target = resolveEnvTarget(ctx);
                        if (target != null) {
                            reportRestart(ctx, target);
                        }
                        return Command.SINGLE_SUCCESS;
                    }));
    }

    // --- suggestion providers ---

    private SuggestionProvider<CommandSourceStack> animationSuggestions() {
        return (ctx, builder) -> {
            animationNames.get().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> placementIdSuggestions() {
        return (ctx, builder) -> {
            String animation = tryGetString(ctx, "animation");
            for (Placement p : data.placements()) {
                if (animation == null || p.animation().equals(animation)) {
                    builder.suggest(p.id());
                }
            }
            return builder.buildFuture();
        };
    }

    /** Both things pause/resume accept, animations first, ids deduplicated across animations. */
    private SuggestionProvider<CommandSourceStack> pauseTargetSuggestions() {
        return (ctx, builder) -> {
            Set<String> words = knownAnimations();
            for (Placement p : data.placements()) {
                words.add(p.id());
            }
            words.forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /**
     * Both forms an env/restart target may take: every animation name, and every
     * {@code animation/id} placement key — offered together so the layer being addressed is a
     * choice the user makes from the list rather than a syntax they have to know.
     */
    private SuggestionProvider<CommandSourceStack> envTargetSuggestions() {
        return (ctx, builder) -> {
            Set<String> words = knownAnimations();
            for (Placement p : data.placements()) {
                words.add(p.key());
            }
            words.forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /** The keys already on the addressed layer, plus {@code type} — the one the host acts on. */
    private SuggestionProvider<CommandSourceStack> envKeySuggestions() {
        return (ctx, builder) -> {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(Env.TYPE);
            String token = tryGetString(ctx, "target");
            if (token != null) {
                EnvTarget target = EnvTarget.resolve(token, knownAnimations(), data.placements());
                switch (target.kind()) {
                    case ANIMATION -> data.existingAnimation(target.animation())
                            .ifPresent(s -> keys.addAll(s.env().keySet()));
                    case PLACEMENT -> data.placement(target.animation(), target.id())
                            .ifPresent(p -> keys.addAll(p.env().keySet()));
                    case UNKNOWN -> { }
                }
            }
            keys.forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> groupSuggestions() {
        return (ctx, builder) -> {
            data.groupIds().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (ctx, builder) -> {
            for (Player p : server.getOnlinePlayers()) {
                builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
    }

    /** What is actually on the addressed placement's list — the only removable words. */
    private SuggestionProvider<CommandSourceStack> filterEntrySuggestions(boolean whitelist) {
        return (ctx, builder) -> {
            String animation = tryGetString(ctx, "animation");
            String id = tryGetString(ctx, "id");
            if (animation != null && id != null) {
                data.placement(animation, id)
                        .ifPresent(p -> p.filter(whitelist).forEach(builder::suggest));
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> playerOrGroupSuggestions() {
        return (ctx, builder) -> {
            for (Player p : server.getOnlinePlayers()) {
                builder.suggest(p.getName());
            }
            data.groupIds().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /** The three coordinate arguments, each knowing which of a player's coordinates it mirrors. */
    private enum Axis {
        X, Y, Z;

        /** The argument name in the command tree — lower case, like every other argument. */
        String argument() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        double of(Player player) {
            return switch (this) {
                case X -> player.getX();
                case Y -> player.getY();
                case Z -> player.getZ();
            };
        }
    }

    /**
     * What one coordinate offers: the player's own floored coordinates as cumulative runs from
     * this axis to {@code z} — on {@code x} that is {@code "10"}, {@code "10 20"} and
     * {@code "10 20 30"} — so one click can fill everything that remains. A suggestion with
     * spaces spans nodes: the client inserts the text and the parser re-splits it into the
     * following arguments. {@code ~} is deliberately not offered (shorter to type than to pick),
     * and console gets nothing: it has no position, so any hint would be a lie.
     */
    private static SuggestionProvider<CommandSourceStack> coordinateSuggestions(Axis axis) {
        return (ctx, builder) -> {
            if (ctx.getSource().getSender() instanceof Player p) {
                StringBuilder run = new StringBuilder();
                for (Axis a : Axis.values()) {
                    if (a.compareTo(axis) < 0) {
                        continue;
                    }
                    if (!run.isEmpty()) {
                        run.append(' ');
                    }
                    run.append((long) Math.floor(a.of(p)));
                    builder.suggest(run.toString());
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> literals(String... options) {
        return (ctx, builder) -> {
            for (String o : options) {
                builder.suggest(o);
            }
            return builder.buildFuture();
        };
    }

    // --- helpers ---

    private static String tryGetString(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An optional numeric argument's value, or {@code 0} when that branch was not taken. */
    private static double optionalDouble(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return DoubleArgumentType.getDouble(ctx, name);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String miniMessage) {
        Messages.send(ctx.getSource().getSender(), miniMessage);
    }

    private static String worldOf(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player p && p.getWorld() != null) {
            return p.getWorld().getName();
        }
        return "world";
    }

    /**
     * How every set the command layer prints is rendered: the escaped entries joined with
     * {@code ", "}, or a literal {@code (none)} — never Java's {@code [a, b]}, whose brackets read
     * as syntax the user could have typed.
     */
    private static String entries(Collection<String> values) {
        return values.isEmpty()
                ? "(none)"
                : values.stream().map(BillboardCommand::esc).collect(Collectors.joining(", "));
    }

    private static String esc(String raw) {
        return MessageFormats.escape(raw == null ? "" : raw);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static double sq(double v) {
        return v * v;
    }
}
