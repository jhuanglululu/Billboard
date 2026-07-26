package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.config.BillboardConfig;
import com.jhuanglululu.billboard.data.AnimationSettings;
import com.jhuanglululu.billboard.data.DataStore;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import com.jhuanglululu.billboard.load.ReloadSummary;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.message.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The {@code /billboard} Brigadier command tree (spawn/remove/resume/list/whitelist/blacklist/
 * group/set), following the design's rule that literal subcommands precede fields. Handlers
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

    private BillboardCommand(DataStore data, Runnable save, Supplier<Set<String>> animationNames,
            Server server, Supplier<BillboardConfig> config, Supplier<ReloadSummary> reload,
            Supplier<int[]> exportRegistry) {
        this.data = data;
        this.save = save;
        this.animationNames = animationNames;
        this.server = server;
        this.config = config;
        this.reload = reload;
        this.exportRegistry = exportRegistry;
    }

    /** Register {@code /billboard} on the plugin's command lifecycle. */
    public static void register(JavaPlugin plugin, DataStore data, Runnable save,
            Supplier<Set<String>> animationNames, Server server, Supplier<BillboardConfig> config,
            Supplier<ReloadSummary> reload, Supplier<int[]> exportRegistry) {
        BillboardCommand cmd = new BillboardCommand(data, save, animationNames, server, config,
                reload, exportRegistry);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(cmd.build(), "Billboard animation control", List.of("bb")));
    }

    /** Permission gating the admin subcommands (default op). */
    public static final String PERMISSION = "billboard.admin";

    // Access matrix (see build()): admins get every subcommand; config log-viewers get ONLY
    // /billboard log; everyone else sees nothing (root .requires fails).
    private boolean isAdmin(CommandSourceStack src) {
        return src.getSender().hasPermission(PERMISSION);
    }

    private boolean isLogViewer(CommandSourceStack src) {
        return config.get().logViewers().contains(src.getSender().getName());
    }

    private boolean rootAccess(CommandSourceStack src) {
        return isAdmin(src) || isLogViewer(src);
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        // Root is reachable by admins OR config log-viewers; each admin subcommand re-checks
        // admin, and /billboard log checks log-viewer-or-admin — so a non-admin log-viewer can
        // reach only `log`, and a non-admin non-viewer reaches nothing.
        return Commands.literal("billboard")
                .requires(this::rootAccess)
                .then(spawn())
                .then(remove())
                .then(resume())
                .then(reload())
                .then(export())
                .then(list())
                .then(listFilter("whitelist"))
                .then(listFilter("blacklist"))
                .then(group())
                .then(set())
                .then(log())
                .build();
    }

    // --- spawn <animation> <id> <x y z> <type> <visibility> ---

    private LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return Commands.literal("spawn").requires(this::isAdmin).then(
            Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
            Commands.argument("id", StringArgumentType.word()).then(
            Commands.argument("x", DoubleArgumentType.doubleArg()).then(
            Commands.argument("y", DoubleArgumentType.doubleArg()).then(
            Commands.argument("z", DoubleArgumentType.doubleArg()).then(
            Commands.argument("type", StringArgumentType.word()).suggests(literals("shared", "per_player")).then(
            Commands.argument("visibility", StringArgumentType.word())
                    .suggests(literals("everyone", "none", "whitelist", "blacklist"))
                    .executes(this::doSpawn))))))));
    }

    private int doSpawn(CommandContext<CommandSourceStack> ctx) {
        String animation = StringArgumentType.getString(ctx, "animation");
        String id = StringArgumentType.getString(ctx, "id");
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        String world = worldOf(ctx);
        Optional<String> unknown = SpawnValidator.rejectUnknown(animation, animationNames.get());
        if (unknown.isPresent()) {
            reply(ctx, unknown.get());
            return Command.SINGLE_SUCCESS;
        }
        try {
            InstanceType type = InstanceType.fromWire(StringArgumentType.getString(ctx, "type"));
            VisibilityMode visibility = VisibilityMode.fromWire(StringArgumentType.getString(ctx, "visibility"));
            data.putPlacement(new Placement(animation, id, world, x, y, z, type, visibility));
            save.run();
            reply(ctx, "<green>Placed <white>" + esc(animation) + "/" + esc(id) + "</white> at "
                    + fmt(x) + " " + fmt(y) + " " + fmt(z) + " in " + esc(world) + "</green>");
        } catch (IllegalArgumentException e) {
            reply(ctx, "<red>" + esc(e.getMessage()) + "</red>");
        }
        return Command.SINGLE_SUCCESS;
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
                            reply(ctx, "<green>Removed <white>" + esc(animation) + "/" + esc(id) + "</white></green>");
                        } else {
                            reply(ctx, "<red>No such placement " + esc(animation) + "/" + esc(id) + "</red>");
                        }
                        return Command.SINGLE_SUCCESS;
                    })));
    }

    // --- resume <animation> ---

    private LiteralArgumentBuilder<CommandSourceStack> resume() {
        return Commands.literal("resume").requires(this::isAdmin).then(
            Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions())
                    .executes(ctx -> {
                        String animation = StringArgumentType.getString(ctx, "animation");
                        data.animation(animation).setPaused(false);
                        save.run();
                        reply(ctx, "<green>Cleared the error-pause on <white>" + esc(animation) + "</white></green>");
                        return Command.SINGLE_SUCCESS;
                    }));
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
            hover.append("added: ").append(esc(String.valueOf(s.diff().added())))
                    .append("\nchanged: ").append(esc(String.valueOf(s.diff().changed())))
                    .append("\nremoved: ").append(esc(String.valueOf(s.diff().removed())));
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
        String hover = "<gray>plugins/Billboard/registry.rs</gray>"
                + "\n<gray>point $BILLBOARD_REGISTRY at it and rebuild the animation</gray>";
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
            reply(ctx, "<red>You are not a log viewer, so there is nothing to " + (mute ? "mute" : "unmute")
                    + ".</red>");
            return Command.SINGLE_SUCCESS;
        }
        if (mute) {
            data.logMuted().add(name);
        } else {
            data.logMuted().remove(name);
        }
        save.run();
        reply(ctx, "<green>Guest log output is now <white>" + (mute ? "muted" : "unmuted")
                + "</white> for you.</green>");
        return Command.SINGLE_SUCCESS;
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
        reply(ctx, MessageFormats.PREFIX + "<gray>placements:</gray>");
        for (Placement p : data.placements()) {
            boolean paused = data.existingAnimation(p.animation()).map(AnimationSettings::paused).orElse(false);
            String line = "<white>" + esc(p.key()) + "</white> <gray>(" + p.type().wire() + ", "
                    + p.visibility().wire() + (paused ? ", <red>paused</red>" : "") + ")</gray>";
            String hover = "world " + esc(p.world()) + " at " + fmt(p.x()) + " " + fmt(p.y()) + " " + fmt(p.z());
            ctx.getSource().getSender().sendMessage(Messages.withHover(line, hover));
        }
    }

    private void listAnimation(CommandContext<CommandSourceStack> ctx, String animation) {
        AnimationSettings s = data.existingAnimation(animation).orElse(new AnimationSettings());
        reply(ctx, MessageFormats.PREFIX + "<white>" + esc(animation) + "</white> "
                + (s.paused() ? "<red>[paused]</red> " : "")
                + "<gray>whitelist=" + esc(String.valueOf(s.whitelist()))
                + " blacklist=" + esc(String.valueOf(s.blacklist())) + "</gray>");
        for (Placement p : data.placements()) {
            if (!p.animation().equals(animation)) {
                continue;
            }
            reply(ctx, "  <white>" + esc(p.id()) + "</white> <gray>(" + p.type().wire() + ", "
                    + p.visibility().wire() + ")</gray> <dark_gray>viewers: " + eligibleNames(p) + "</dark_gray>");
        }
    }

    private String eligibleNames(Placement p) {
        AnimationSettings s = data.existingAnimation(p.animation()).orElse(new AnimationSettings());
        double radius = config.get().proximity().radius();
        StringBuilder sb = new StringBuilder();
        for (Player pl : server.getOnlinePlayers()) {
            var loc = pl.getLocation();
            String world = loc.getWorld() == null ? "" : loc.getWorld().getName();
            boolean inRange = world.equals(p.world())
                    && sq(loc.getX() - p.x()) + sq(loc.getY() - p.y()) + sq(loc.getZ() - p.z()) <= radius * radius;
            boolean visible = com.jhuanglululu.billboard.placement.Eligibility.visibleTo(
                    p.visibility(), s.whitelist(), s.blacklist(), data.groupsView(), pl.getName());
            if (inRange && visible) {
                sb.append(sb.isEmpty() ? "" : ", ").append(esc(pl.getName()));
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    // --- whitelist|blacklist <add|remove|list> <animation> <player|group> ---

    private LiteralArgumentBuilder<CommandSourceStack> listFilter(String which) {
        boolean whitelist = which.equals("whitelist");
        return Commands.literal(which)
                .requires(this::isAdmin)
                .then(Commands.literal("add").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
                    Commands.argument("entry", StringArgumentType.word()).suggests(playerOrGroupSuggestions())
                            .executes(ctx -> editFilter(ctx, whitelist, true)))))
                .then(Commands.literal("remove").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions()).then(
                    Commands.argument("entry", StringArgumentType.word())
                            .executes(ctx -> editFilter(ctx, whitelist, false)))))
                .then(Commands.literal("list").then(
                    Commands.argument("animation", StringArgumentType.word()).suggests(animationSuggestions())
                            .executes(ctx -> {
                                String animation = StringArgumentType.getString(ctx, "animation");
                                Set<String> set = filterSet(animation, whitelist);
                                reply(ctx, MessageFormats.PREFIX + "<white>" + esc(animation) + "</white> " + which
                                        + ": <gray>" + esc(String.valueOf(set)) + "</gray>");
                                return Command.SINGLE_SUCCESS;
                            })));
    }

    private int editFilter(CommandContext<CommandSourceStack> ctx, boolean whitelist, boolean add) {
        String animation = StringArgumentType.getString(ctx, "animation");
        String entry = StringArgumentType.getString(ctx, "entry");
        Set<String> set = filterSet(animation, whitelist);
        if (add) {
            set.add(entry);
        } else {
            set.remove(entry);
        }
        save.run();
        reply(ctx, "<green>" + (add ? "Added " : "Removed ") + esc(entry) + " "
                + (add ? "to " : "from ") + (whitelist ? "whitelist" : "blacklist") + " of <white>"
                + esc(animation) + "</white></green>");
        return Command.SINGLE_SUCCESS;
    }

    private Set<String> filterSet(String animation, boolean whitelist) {
        AnimationSettings s = data.animation(animation);
        return whitelist ? s.whitelist() : s.blacklist();
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
                        reply(ctx, "<green>Created group <white>" + esc(id) + "</white></green>");
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
                        reply(ctx, MessageFormats.PREFIX + "groups: <gray>" + esc(String.valueOf(data.groupIds())) + "</gray>");
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(groupSuggestions())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                reply(ctx, MessageFormats.PREFIX + "<white>" + esc(id) + "</white>: <gray>"
                                        + esc(String.valueOf(data.group(id))) + "</gray>");
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
        reply(ctx, "<green>" + (add ? "Added " : "Removed ") + esc(player) + " "
                + (add ? "to " : "from ") + "group <white>" + esc(id) + "</white></green>");
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
        try {
            int changed = 0;
            for (Placement p : List.copyOf(data.placements())) {
                if (!p.animation().equals(animation)) {
                    continue;
                }
                Placement updated = switch (field) {
                    case "visibility" -> p.withVisibility(VisibilityMode.fromWire(value));
                    case "type" -> p.withType(InstanceType.fromWire(value));
                    default -> throw new IllegalArgumentException("unknown field: " + field);
                };
                data.putPlacement(updated);
                changed++;
            }
            save.run();
            reply(ctx, "<green>Set " + esc(field) + "=" + esc(value) + " on " + changed
                    + " placement(s) of <white>" + esc(animation) + "</white></green>");
        } catch (IllegalArgumentException e) {
            reply(ctx, "<red>" + esc(e.getMessage()) + "</red>");
        }
        return Command.SINGLE_SUCCESS;
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

    private SuggestionProvider<CommandSourceStack> playerOrGroupSuggestions() {
        return (ctx, builder) -> {
            for (Player p : server.getOnlinePlayers()) {
                builder.suggest(p.getName());
            }
            data.groupIds().forEach(builder::suggest);
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

    private static void reply(CommandContext<CommandSourceStack> ctx, String miniMessage) {
        Messages.send(ctx.getSource().getSender(), miniMessage);
    }

    private static String worldOf(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player p && p.getWorld() != null) {
            return p.getWorld().getName();
        }
        return "world";
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
