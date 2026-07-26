package com.jhuanglululu.billboard.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.data.AnimationSettings;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The data.toml cross-checks: each placement must name a loaded animation and an existing world, and
 * the visibility list its mode actually consults must be usable. Every failure skips only that
 * placement — the property that keeps one bad entry from taking the file down.
 */
class DataCheckTest {

    private static final Set<String> LOADED = Set.of("demo", "spiral");
    private static final Set<String> WORLDS = Set.of("world", "world_nether");

    private static Placement placement(String animation, String id, String world) {
        return placement(animation, id, world, VisibilityMode.EVERYONE);
    }

    private static Placement placement(String animation, String id, String world,
            VisibilityMode visibility) {
        return new Placement(animation, id, world, 0, 64, 0, InstanceType.SHARED, visibility);
    }

    private static List<LoadIssue> check(List<Placement> placements,
            Map<String, AnimationSettings> settings, Set<String> groups) {
        return DataCheck.check(placements, LOADED, WORLDS, settings, groups);
    }

    @Test
    void aValidPlacementProducesNoIssue() {
        assertEquals(List.of(), check(List.of(placement("demo", "one", "world")), Map.of(), Set.of()));
    }

    @Test
    void placementReferencingAMissingAnimationIsSkipped() {
        List<LoadIssue> issues = check(List.of(placement("ghost", "one", "world")), Map.of(), Set.of());

        assertEquals(1, issues.size());
        LoadIssue issue = issues.getFirst();
        assertEquals(LoadIssue.Scope.PLACEMENT, issue.scope());
        assertEquals("ghost/one", issue.subject());
        assertTrue(issue.detail().contains("animation \"ghost\" is not loaded"), issue.detail());
        assertEquals(Set.of("ghost/one"), DataCheck.skippedKeys(issues));
    }

    @Test
    void placementInAMissingWorldIsSkipped() {
        List<LoadIssue> issues = check(List.of(placement("demo", "one", "the_end")), Map.of(), Set.of());

        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("world \"the_end\" does not exist"),
                issues.getFirst().detail());
    }

    @Test
    void aMissingAnimationIsReportedOnceNotAlsoAsAWorldProblem() {
        // Both are wrong; one issue per placement keeps the report readable.
        List<LoadIssue> issues = check(List.of(placement("ghost", "one", "nowhere")), Map.of(), Set.of());
        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("not loaded"));
    }

    @Test
    void unknownGroupInTheConsultedListIsSkipped() {
        AnimationSettings settings = new AnimationSettings();
        settings.whitelist().add("vip-team"); // hyphen: cannot be a player name, so it must be a group
        List<LoadIssue> issues = check(
                List.of(placement("demo", "one", "world", VisibilityMode.WHITELIST)),
                Map.of("demo", settings), Set.of("staff"));

        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("visibility entry \"vip-team\""),
                issues.getFirst().detail());
    }

    @Test
    void aKnownGroupPasses() {
        AnimationSettings settings = new AnimationSettings();
        settings.whitelist().add("vip-team");
        assertEquals(List.of(), check(
                List.of(placement("demo", "one", "world", VisibilityMode.WHITELIST)),
                Map.of("demo", settings), Set.of("vip-team")));
    }

    @Test
    void aPlainPlayerNamePassesWithoutBeingAGroup() {
        AnimationSettings settings = new AnimationSettings();
        settings.whitelist().add("Steve");
        settings.whitelist().add("Player_123");
        assertEquals(List.of(), check(
                List.of(placement("demo", "one", "world", VisibilityMode.WHITELIST)),
                Map.of("demo", settings), Set.of()));
    }

    @Test
    void onlyTheListTheModeConsultsIsChecked() {
        // A stale whitelist under blacklist visibility changes nothing on screen: not an issue.
        AnimationSettings settings = new AnimationSettings();
        settings.whitelist().add("bad entry!");
        assertEquals(List.of(), check(
                List.of(placement("demo", "one", "world", VisibilityMode.BLACKLIST)),
                Map.of("demo", settings), Set.of()));

        // The same entry on the blacklist, which blacklist visibility does consult, is reported.
        AnimationSettings consulted = new AnimationSettings();
        consulted.blacklist().add("bad entry!");
        assertEquals(1, check(List.of(placement("demo", "one", "world", VisibilityMode.BLACKLIST)),
                Map.of("demo", consulted), Set.of()).size());
    }

    @Test
    void everyoneAndNoneConsultNoLists() {
        AnimationSettings settings = new AnimationSettings();
        settings.whitelist().add("!!!");
        settings.blacklist().add("!!!");
        for (VisibilityMode mode : List.of(VisibilityMode.EVERYONE, VisibilityMode.NONE)) {
            assertEquals(List.of(), check(List.of(placement("demo", "one", "world", mode)),
                    Map.of("demo", settings), Set.of()), "mode " + mode);
        }
    }

    @Test
    void oneBadPlacementDoesNotSkipTheOthers() {
        List<Placement> placements = List.of(
                placement("demo", "good", "world"),
                placement("ghost", "bad", "world"),
                placement("spiral", "alsogood", "world_nether"),
                placement("demo", "badworld", "the_end"));

        List<LoadIssue> issues = check(placements, Map.of(), Set.of());

        // Exactly the two broken ones are skipped; the healthy pair is untouched.
        assertEquals(Set.of("ghost/bad", "demo/badworld"), DataCheck.skippedKeys(issues));
        assertEquals(2, issues.size());
    }

    @Test
    void missingSettingsAreNotAnIssue() {
        // A placement whose animation has no persisted settings row has empty lists by definition.
        Map<String, AnimationSettings> none = new HashMap<>();
        assertEquals(List.of(), check(
                List.of(placement("demo", "one", "world", VisibilityMode.WHITELIST)), none, Set.of()));
    }

    @Test
    void issuesRenderAShortLineWithDetailInTheHover() {
        LoadIssue issue = check(List.of(placement("ghost", "one", "world")), Map.of(), Set.of())
                .getFirst();

        assertTrue(issue.line().startsWith("<gray>[</gray><aqua>Billboard</aqua><gray>]</gray> "),
                issue.line());
        assertTrue(issue.line().contains("Skipped placement <white>ghost/one</white>"), issue.line());
        assertTrue(issue.line().contains("hover for details"), issue.line());
        assertTrue(issue.hover().contains("is not loaded"), issue.hover());
        assertTrue(issue.hover().contains("/billboard reload"), issue.hover());
        assertTrue(issue.plain().startsWith("Skipped placement \"ghost/one\": "), issue.plain());
    }

    @Test
    void untrustedNamesAreEscapedInTheReport() {
        // A placement id is user input, and it reaches the visible line as the subject.
        LoadIssue subject = check(List.of(placement("ghost", "<red>x", "world")), Map.of(), Set.of())
                .getFirst();
        assertTrue(subject.line().contains("ghost/\\<red>x"), subject.line());

        // A world name is user input too, and it reaches the hover through the detail.
        LoadIssue detail = check(List.of(placement("demo", "one", "<click>evil")), Map.of(), Set.of())
                .getFirst();
        assertTrue(detail.hover().contains("\\<click>evil"), detail.hover());
    }
}
