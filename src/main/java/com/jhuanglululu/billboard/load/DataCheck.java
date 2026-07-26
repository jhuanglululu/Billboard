package com.jhuanglululu.billboard.load;

import com.jhuanglululu.billboard.data.AnimationSettings;
import com.jhuanglululu.billboard.data.Placement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Load-time cross-checks of {@code data.toml} against the world it has to run in: every placement
 * must name an animation that loaded and a world that exists, and every visibility entry must be
 * usable. Pure — no Bukkit, no file access — so every decision is unit-testable; the caller passes
 * the loaded animation names and the server's world names in.
 *
 * <p>A failing placement is <em>skipped</em>, not fatal: it is reported and behaves as paused until
 * a successful reload, while every other placement loads normally. One broken entry never blocks
 * the file.
 */
public final class DataCheck {

    private DataCheck() {}

    /**
     * A Minecraft account name: 3–16 characters of {@code [A-Za-z0-9_]}. Visibility entries are
     * player names <em>or</em> group ids with no syntax to tell them apart, so an entry that
     * matches no known group can only be judged by whether it could be a name at all.
     */
    private static final int NAME_MIN = 3;
    private static final int NAME_MAX = 16;

    /**
     * Checks every placement.
     *
     * @param placements the persisted placements
     * @param loaded     the animation names whose modules validated
     * @param worlds     the world names the server has
     * @param settings   per-animation settings lookup (whitelist/blacklist), may return null
     * @param groupIds   the known group ids
     * @return one issue per unusable placement, in placement order
     */
    public static List<LoadIssue> check(Collection<Placement> placements, Set<String> loaded,
            Set<String> worlds, Function<String, AnimationSettings> settings, Set<String> groupIds) {
        List<LoadIssue> issues = new ArrayList<>();
        for (Placement p : placements) {
            if (!loaded.contains(p.animation())) {
                issues.add(LoadIssue.placement(p.key(), "animation \"" + p.animation()
                        + "\" is not loaded (missing or failed to validate)"));
                continue; // one issue per placement: the animation being gone subsumes the rest
            }
            if (!worlds.contains(p.world())) {
                issues.add(LoadIssue.placement(p.key(),
                        "world \"" + p.world() + "\" does not exist on this server"));
                continue;
            }
            String bad = unusableEntry(p, settings.apply(p.animation()), groupIds);
            if (bad != null) {
                issues.add(LoadIssue.placement(p.key(), "visibility entry \"" + bad
                        + "\" is neither a known group nor a valid player name"));
            }
        }
        return issues;
    }

    /**
     * The first entry of the list this placement's visibility mode actually consults that is
     * neither a known group nor a possible player name, or {@code null} if they are all usable.
     * Only the consulted list is checked: a stale whitelist under {@code visibility = blacklist}
     * changes nothing on screen, so reporting it would be noise.
     */
    private static String unusableEntry(Placement p, AnimationSettings settings, Set<String> groups) {
        if (settings == null) {
            return null;
        }
        Set<String> consulted = switch (p.visibility()) {
            case WHITELIST -> settings.whitelist();
            case BLACKLIST -> settings.blacklist();
            case EVERYONE, NONE -> Set.of();
        };
        for (String entry : consulted) {
            if (!groups.contains(entry) && !couldBePlayerName(entry)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean couldBePlayerName(String entry) {
        if (entry.length() < NAME_MIN || entry.length() > NAME_MAX) {
            return false;
        }
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            boolean allowed = c == '_' || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** The keys of the placements the issues skip, for the runtime's skip set. */
    public static Set<String> skippedKeys(List<LoadIssue> issues) {
        Set<String> keys = new LinkedHashSet<>();
        for (LoadIssue issue : issues) {
            if (issue.scope() == LoadIssue.Scope.PLACEMENT) {
                keys.add(issue.subject());
            }
        }
        return keys;
    }

    /** Convenience overload taking the settings map directly. */
    public static List<LoadIssue> check(Collection<Placement> placements, Set<String> loaded,
            Set<String> worlds, Map<String, AnimationSettings> settings, Set<String> groupIds) {
        return check(placements, loaded, worlds, settings::get, groupIds);
    }
}
