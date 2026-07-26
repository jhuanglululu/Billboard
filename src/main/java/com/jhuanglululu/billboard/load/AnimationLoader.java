package com.jhuanglululu.billboard.load;

import com.jhuanglululu.wasm.Module;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans the animations folder and validates every {@code .wasm} in it through {@link ModuleCheck},
 * at server start and on every {@code /billboard reload}. A file that fails is skipped with an
 * issue and the rest load normally — one broken animation never blocks the folder.
 *
 * <p>This is the primary gate for animation health. Nothing about a module's validity is discovered
 * later: by the time the proximity controller can start an instance, every module in the map has
 * already parsed, instantiated and passed the ABI handshake.
 *
 * <p>No Bukkit — plain file IO — so it is testable against a temporary directory.
 */
public final class AnimationLoader {

    private AnimationLoader() {}

    /**
     * The outcome of one scan.
     *
     * @param modules validated modules by animation name (the {@code .wasm} file stem)
     * @param hashes  content hashes by name, for {@link AnimationReloadDiff}
     * @param issues  one per file that was skipped, in file-name order
     */
    public record Result(Map<String, Module> modules, Map<String, Integer> hashes,
            List<LoadIssue> issues) {

        public Result {
            modules = Map.copyOf(modules);
            hashes = Map.copyOf(hashes);
            issues = List.copyOf(issues);
        }
    }

    /** Scans {@code dir}, creating it if absent. Never throws: an unreadable folder is an issue. */
    public static Result load(Path dir) {
        Map<String, Module> modules = new LinkedHashMap<>();
        Map<String, Integer> hashes = new LinkedHashMap<>();
        List<LoadIssue> issues = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            issues.add(LoadIssue.animation("(folder)",
                    "cannot create the animations folder: " + e.getMessage()));
            return new Result(modules, hashes, issues);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".wasm")).sorted()
                    .forEach(file -> validate(file, modules, hashes, issues));
        } catch (IOException e) {
            issues.add(LoadIssue.animation("(folder)",
                    "cannot scan the animations folder: " + e.getMessage()));
        }
        return new Result(modules, hashes, issues);
    }

    private static void validate(Path file, Map<String, Module> modules,
            Map<String, Integer> hashes, List<LoadIssue> issues) {
        String name = file.getFileName().toString().replaceFirst("\\.wasm$", "");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            issues.add(LoadIssue.animation(name, "cannot be read: " + e.getMessage()));
            return;
        }
        ModuleCheck.Result checked = ModuleCheck.check(bytes);
        if (!checked.ok()) {
            issues.add(LoadIssue.animation(name, checked.error().orElseThrow()));
            return;
        }
        modules.put(name, checked.module());
        hashes.put(name, Arrays.hashCode(bytes));
    }
}
