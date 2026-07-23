package com.jhuanglululu.billboard.data;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The plugin-managed persistent state (data.toml): placements, per-animation settings
 * (paused flag + whitelist/blacklist), and groups. In-memory and mutable; the plugin
 * calls {@link #save} after any change. Backed by night-config; this class touches no
 * Bukkit API so it is fully unit-testable.
 *
 * <p>Schema (arrays of tables, so animation/group names never collide with TOML paths):
 * <pre>
 * [[placements]] animation, id, world, x, y, z, type, visibility
 * [[animations]] name, paused, whitelist = [...], blacklist = [...]
 * [[groups]]     id, players = [...]
 * </pre>
 */
public final class DataStore {

    private final Map<String, Placement> placements = new TreeMap<>();
    private final Map<String, AnimationSettings> animations = new TreeMap<>();
    private final Map<String, Set<String>> groups = new TreeMap<>();
    private final Set<String> logMuted = new LinkedHashSet<>();

    // --- log muting ---

    /** The live set of player names who muted guest-log output via {@code /billboard log off}. */
    public Set<String> logMuted() {
        return logMuted;
    }

    // --- placements ---

    public void putPlacement(Placement p) {
        placements.put(p.key(), p);
    }

    public Optional<Placement> removePlacement(String animation, String id) {
        return Optional.ofNullable(placements.remove(animation + "/" + id));
    }

    public Optional<Placement> placement(String animation, String id) {
        return Optional.ofNullable(placements.get(animation + "/" + id));
    }

    public Collection<Placement> placements() {
        return placements.values();
    }

    // --- animation settings ---

    /** Settings for {@code animation}, creating an empty (unpaused) entry if absent. */
    public AnimationSettings animation(String animation) {
        return animations.computeIfAbsent(animation, k -> new AnimationSettings());
    }

    /** Settings for {@code animation} only if one already exists. */
    public Optional<AnimationSettings> existingAnimation(String animation) {
        return Optional.ofNullable(animations.get(animation));
    }

    // --- groups ---

    public Set<String> group(String id) {
        return groups.computeIfAbsent(id, k -> new LinkedHashSet<>());
    }

    public boolean hasGroup(String id) {
        return groups.containsKey(id);
    }

    public Optional<Set<String>> existingGroup(String id) {
        return Optional.ofNullable(groups.get(id));
    }

    public Collection<String> groupIds() {
        return groups.keySet();
    }

    /** A read-only view of all groups (group id -> player names), for eligibility checks. */
    public Map<String, Set<String>> groupsView() {
        return groups;
    }

    // --- persistence ---

    public static DataStore load(Path file) {
        DataStore store = new DataStore();
        try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                .preserveInsertionOrder().sync().build()) {
            config.load();
            store.readFrom(config);
        }
        return store;
    }

    public void save(Path file) {
        try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                .preserveInsertionOrder().sync().build()) {
            writeTo(config);
            config.save();
        }
    }

    private void readFrom(Config config) {
        List<Config> placementList = config.getOrElse("placements", List.of());
        for (Config c : placementList) {
            Placement p = new Placement(
                    c.get("animation"), c.get("id"), c.get("world"),
                    num(c, "x"), num(c, "y"), num(c, "z"),
                    InstanceType.fromWire(c.get("type")),
                    VisibilityMode.fromWire(c.get("visibility")));
            placements.put(p.key(), p);
        }
        List<Config> animationList = config.getOrElse("animations", List.of());
        for (Config c : animationList) {
            AnimationSettings s = animation(c.get("name"));
            s.setPaused(c.getOrElse("paused", false));
            s.whitelist().addAll(c.getOrElse("whitelist", List.<String>of()));
            s.blacklist().addAll(c.getOrElse("blacklist", List.<String>of()));
        }
        List<Config> groupList = config.getOrElse("groups", List.of());
        for (Config c : groupList) {
            group(c.get("id")).addAll(c.getOrElse("players", List.<String>of()));
        }
        logMuted.addAll(config.getOrElse("log-muted", List.<String>of()));
    }

    private void writeTo(Config config) {
        List<Config> placementList = new ArrayList<>();
        for (Placement p : placements.values()) {
            Config c = Config.inMemory();
            c.set("animation", p.animation());
            c.set("id", p.id());
            c.set("world", p.world());
            c.set("x", p.x());
            c.set("y", p.y());
            c.set("z", p.z());
            c.set("type", p.type().wire());
            c.set("visibility", p.visibility().wire());
            placementList.add(c);
        }
        config.set("placements", placementList);

        List<Config> animationList = new ArrayList<>();
        for (Map.Entry<String, AnimationSettings> e : animations.entrySet()) {
            Config c = Config.inMemory();
            c.set("name", e.getKey());
            c.set("paused", e.getValue().paused());
            c.set("whitelist", new ArrayList<>(e.getValue().whitelist()));
            c.set("blacklist", new ArrayList<>(e.getValue().blacklist()));
            animationList.add(c);
        }
        config.set("animations", animationList);

        List<Config> groupList = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            Config c = Config.inMemory();
            c.set("id", e.getKey());
            c.set("players", new ArrayList<>(e.getValue()));
            groupList.add(c);
        }
        config.set("groups", groupList);

        config.set("log-muted", new ArrayList<>(logMuted));
    }

    private static double num(Config c, String key) {
        Object v = c.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("expected a number for \"" + key + "\" but got " + v);
    }
}
