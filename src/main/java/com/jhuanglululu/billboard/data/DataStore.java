package com.jhuanglululu.billboard.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The plugin-managed persistent state: placements (including their visibility lists),
 * per-animation settings (the paused flag), groups, and per-player log mutes. In-memory and
 * mutable; the plugin calls {@link #save} after any change. No Bukkit API, so it is fully
 * unit-testable.
 *
 * <p>It lives in a <b>folder</b> ({@code plugins/Billboard/data/}) of three files:
 * <pre>
 * data.json          {"groups": {id: [players]}, "log-muted": [names]}
 * animations.jsonl   one object per line: name, paused
 * placements.jsonl   one object per line: animation, id, world, x, y, z, type, visibility,
 *                    paused, whitelist, blacklist
 * </pre>
 *
 * <p><b>Why line-delimited JSON.</b> The two growing collections are records, and one corrupt
 * record must not cost the others: a line that does not parse is skipped with a loud issue (see
 * {@link #issues()}) and every other line loads, the same philosophy as load-time validation
 * skipping one broken animation instead of the folder. A broken {@code data.json} likewise yields
 * empty groups and mutes rather than blocking startup. Writing is deterministic — fixed field
 * order, sorted keys, one record per line — so the files diff cleanly.
 */
public final class DataStore {

    /** The three files, relative to the data folder. */
    private static final String DATA_JSON = "data.json";
    private static final String ANIMATIONS_JSONL = "animations.jsonl";
    private static final String PLACEMENTS_JSONL = "placements.jsonl";
    private static final String README = "README.txt";

    private static final String README_TEXT = """
            This folder is written by the Billboard plugin.

            data.json, animations.jsonl and placements.jsonl hold live plugin state
            (placements, per-animation settings, groups, log mutes). The plugin rewrites
            them whenever that state changes, so any edit you make while the server is
            running will be overwritten without warning. Stop the server first.

            The .jsonl files hold one JSON object per line. A line that does not parse is
            reported in the server log, skipped at startup, and dropped the next time the
            plugin saves; the other lines load normally.
            """;

    private static final Gson COMPACT = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Placement> placements = new TreeMap<>();
    private final Map<String, AnimationSettings> animations = new TreeMap<>();
    private final Map<String, Set<String>> groups = new TreeMap<>();
    private final Set<String> logMuted = new LinkedHashSet<>();
    private final List<String> issues = new ArrayList<>();

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

    /**
     * Every animation name with a persisted settings entry — including one whose {@code .wasm}
     * no longer loads, which is exactly the animation someone needs to name in {@code resume}.
     */
    public Collection<String> animationNames() {
        return animations.keySet();
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

    /**
     * Everything that could not be read by the last {@link #load}, one plain-text message each,
     * for the caller to report loudly. Empty when the folder loaded cleanly (or was absent).
     */
    public List<String> issues() {
        return List.copyOf(issues);
    }

    /**
     * Reads the data folder. An absent folder is a first run, not an error: the store comes back
     * empty and {@link #save} creates the folder. Unreadable records are skipped and recorded in
     * {@link #issues()}, never thrown — a corrupt file must not stop the server from booting.
     */
    public static DataStore load(Path dir) {
        DataStore store = new DataStore();
        if (!Files.isDirectory(dir)) {
            return store;
        }
        store.readData(dir.resolve(DATA_JSON));
        store.readAnimations(dir.resolve(ANIMATIONS_JSONL));
        store.readPlacements(dir.resolve(PLACEMENTS_JSONL));
        return store;
    }

    /** Writes all three files, creating the folder (and its README) on first save. */
    public void save(Path dir) {
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve(README);
            if (!Files.exists(readme)) {
                Files.writeString(readme, README_TEXT);
            }
            JsonObject root = new JsonObject();
            JsonObject groupsJson = new JsonObject();
            for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
                groupsJson.add(e.getKey(), stringArray(e.getValue()));
            }
            root.add("groups", groupsJson);
            root.add("log-muted", stringArray(logMuted));
            Files.writeString(dir.resolve(DATA_JSON), PRETTY.toJson(root) + "\n");

            List<JsonObject> animationLines = new ArrayList<>();
            for (Map.Entry<String, AnimationSettings> e : animations.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getKey());
                o.addProperty("paused", e.getValue().paused());
                animationLines.add(o);
            }
            writeLines(dir.resolve(ANIMATIONS_JSONL), animationLines);

            List<JsonObject> placementLines = new ArrayList<>();
            for (Placement p : placements.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("animation", p.animation());
                o.addProperty("id", p.id());
                o.addProperty("world", p.world());
                o.addProperty("x", p.x());
                o.addProperty("y", p.y());
                o.addProperty("z", p.z());
                o.addProperty("type", p.type().wire());
                o.addProperty("visibility", p.visibility().wire());
                o.addProperty("paused", p.paused());
                o.add("whitelist", stringArray(p.whitelist()));
                o.add("blacklist", stringArray(p.blacklist()));
                placementLines.add(o);
            }
            writeLines(dir.resolve(PLACEMENTS_JSONL), placementLines);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the Billboard data folder " + dir, e);
        }
    }

    private static void writeLines(Path file, List<JsonObject> records) throws IOException {
        try (Writer out = Files.newBufferedWriter(file)) {
            for (JsonObject record : records) {
                out.write(COMPACT.toJson(record));
                out.write("\n");
            }
        }
    }

    private static JsonArray stringArray(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    private void readData(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            issues.add(DATA_JSON + " is unreadable (" + reason(e)
                    + ") — starting with no groups and no log mutes; it is rewritten on the next save");
            return;
        }
        JsonElement groupsElement = root.get("groups");
        if (groupsElement != null && groupsElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : groupsElement.getAsJsonObject().entrySet()) {
                try {
                    group(e.getKey()).addAll(strings(e.getValue()));
                } catch (RuntimeException ex) {
                    issues.add(DATA_JSON + ": group \"" + e.getKey() + "\" is unreadable ("
                            + reason(ex) + ") — skipped");
                }
            }
        }
        try {
            logMuted.addAll(strings(root.get("log-muted")));
        } catch (RuntimeException e) {
            issues.add(DATA_JSON + ": \"log-muted\" is unreadable (" + reason(e) + ") — skipped");
        }
    }

    /**
     * Reads animations.jsonl. Files written before the visibility lists moved onto the placement
     * still carry {@code whitelist}/{@code blacklist} here; those keys are simply not read, so an
     * old file loads without complaint and is rewritten without them on the next save.
     */
    private void readAnimations(Path file) {
        forEachRecord(file, record -> animation(string(record, "name")).setPaused(bool(record, "paused")));
    }

    private void readPlacements(Path file) {
        forEachRecord(file, record -> {
            Placement p = new Placement(
                    string(record, "animation"), string(record, "id"), string(record, "world"),
                    number(record, "x"), number(record, "y"), number(record, "z"),
                    InstanceType.fromWire(string(record, "type")),
                    VisibilityMode.fromWire(string(record, "visibility")),
                    bool(record, "paused"),
                    new LinkedHashSet<>(strings(record.get("whitelist"))),
                    new LinkedHashSet<>(strings(record.get("blacklist"))));
            placements.put(p.key(), p);
        });
    }

    /**
     * Feeds every non-blank line of a JSONL file to {@code reader}. A line that fails takes only
     * itself down: it is recorded in {@link #issues()} with its line number and the file loads on.
     */
    private void forEachRecord(Path file, Consumer<JsonObject> reader) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            issues.add(file.getFileName() + " could not be read (" + reason(e) + ") — no entries loaded");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String where = file.getFileName() + " line " + (i + 1);
            try {
                reader.accept(JsonParser.parseString(line).getAsJsonObject());
            } catch (RuntimeException e) {
                issues.add(where + " is not a usable record (" + reason(e)
                        + ") — skipped; it is dropped the next time the plugin saves");
            }
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String string(JsonObject record, String key) {
        JsonElement v = record.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string, got " + v);
        }
        return v.getAsString();
    }

    private static double number(JsonObject record, String key) {
        JsonElement v = record.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a number, got " + v);
        }
        return v.getAsDouble();
    }

    private static boolean bool(JsonObject record, String key) {
        JsonElement v = record.get(key);
        if (v == null) {
            return false;
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a boolean, got " + v);
        }
        return v.getAsBoolean();
    }

    private static List<String> strings(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("expected a list of strings, got " + element);
            }
            out.add(e.getAsString());
        }
        return out;
    }
}
