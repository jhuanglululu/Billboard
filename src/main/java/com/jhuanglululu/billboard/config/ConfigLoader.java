package com.jhuanglululu.billboard.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Reads config.toml into a {@link BillboardConfig}. Every key falls back to the template default
 * on its own, so a partially edited — or partially broken — file never breaks startup: a syntax
 * error or a wrongly typed value is reported loudly through the caller's {@code problems}
 * consumer and that key alone reverts to its default. No Bukkit API, so it is unit-testable
 * against a plain file.
 *
 * <p>tomlj reports syntax errors by returning them from {@link TomlParseResult#errors()} rather
 * than throwing, and still hands back everything it managed to parse — which is exactly the
 * behaviour wanted here, so the errors are shouted and the good keys are kept.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Loads {@code file}, reporting anything unreadable to {@code problems}.
     *
     * @param file     the config.toml path; an absent file yields the defaults silently, since the
     *                 plugin writes the template on first run
     * @param problems receives one plain-text message per parse error or unusable key
     */
    public static BillboardConfig load(Path file, Consumer<String> problems) {
        if (!Files.isRegularFile(file)) {
            return BillboardConfig.defaults();
        }
        TomlParseResult toml;
        try {
            toml = Toml.parse(file);
        } catch (IOException e) {
            problems.accept("could not read " + file.getFileName() + " (" + e.getMessage()
                    + ") — using the built-in defaults");
            return BillboardConfig.defaults();
        }
        for (TomlParseError error : toml.errors()) {
            problems.accept(file.getFileName() + " has a syntax error at line "
                    + error.position().line() + ": " + error.getMessage()
                    + " — the keys after it fall back to their defaults");
        }
        return fromToml(toml, problems);
    }

    static BillboardConfig fromToml(TomlTable toml, Consumer<String> problems) {
        BillboardConfig d = BillboardConfig.defaults();
        BillboardConfig.RuntimeSettings runtime = new BillboardConfig.RuntimeSettings(
                (int) longValue(toml, "runtime.threads", d.runtime().threads(), problems),
                (int) longValue(toml, "runtime.pool-shrink-delay-ticks",
                        d.runtime().poolShrinkDelayTicks(), problems),
                longValue(toml, "runtime.instruction-budget", d.runtime().instructionBudget(), problems),
                (int) longValue(toml, "runtime.memory-cap-mib", d.runtime().memoryCapMib(), problems),
                (int) longValue(toml, "runtime.task-stack-bytes", d.runtime().taskStackBytes(), problems));
        BillboardConfig.Proximity proximity = new BillboardConfig.Proximity(
                (int) longValue(toml, "proximity.radius", d.proximity().radius(), problems),
                (int) longValue(toml, "proximity.check-interval", d.proximity().checkInterval(), problems),
                (int) longValue(toml, "proximity.linger-ticks", d.proximity().lingerTicks(), problems));
        BillboardConfig.Snapshots snapshots = new BillboardConfig.Snapshots(
                (int) longValue(toml, "snapshots.player-interval",
                        d.snapshots().playerInterval(), problems));
        List<String> logViewers = stringList(toml, "logging.log-viewers", d.logViewers(), problems);
        boolean consoleLog = value("logging.console",
                () -> toml.getBoolean("logging.console", d::consoleLog), d.consoleLog(), problems);
        return new BillboardConfig(runtime, proximity, snapshots, logViewers, consoleLog);
    }

    private static long longValue(TomlTable toml, String key, long fallback, Consumer<String> problems) {
        return value(key, () -> toml.getLong(key, () -> fallback), fallback, problems);
    }

    private static List<String> stringList(TomlTable toml, String key, List<String> fallback,
            Consumer<String> problems) {
        return value(key, () -> {
            TomlArray array = toml.getArray(key);
            if (array == null) {
                return fallback;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                out.add(array.getString(i));
            }
            return out;
        }, fallback, problems);
    }

    /**
     * One key's value, or its default if the file gives it the wrong type — tomlj throws for a
     * type mismatch, and one operator typo must not cost the other twenty settings.
     */
    private static <T> T value(String key, Supplier<T> read, T fallback, Consumer<String> problems) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            problems.accept("config key \"" + key + "\" is unusable (" + e.getMessage()
                    + ") — using the default " + fallback);
            return fallback;
        }
    }
}
