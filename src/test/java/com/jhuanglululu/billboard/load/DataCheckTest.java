package com.jhuanglululu.billboard.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.data.VisibilityMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The data-store cross-checks: each placement must name a loaded animation and an existing world, and
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

    /** A placement carrying the visibility lists the check consults — they live on it now. */
    private static Placement placement(String animation, String id, String world,
            VisibilityMode visibility, Set<String> whitelist, Set<String> blacklist) {
        return new Placement(animation, id, world, 0, 64, 0, InstanceType.SHARED, visibility,
                false, whitelist, blacklist);
    }

    private static List<LoadIssue> check(List<Placement> placements, Set<String> groups) {
        return DataCheck.check(placements, LOADED, WORLDS, groups);
    }

    @Test
    void aValidPlacementProducesNoIssue() {
        assertEquals(List.of(), check(List.of(placement("demo", "one", "world")), Set.of()));
    }

    @Test
    void placementReferencingAMissingAnimationIsSkipped() {
        List<LoadIssue> issues = check(List.of(placement("ghost", "one", "world")), Set.of());

        assertEquals(1, issues.size());
        LoadIssue issue = issues.getFirst();
        assertEquals(LoadIssue.Scope.PLACEMENT, issue.scope());
        assertEquals("ghost/one", issue.subject());
        assertTrue(issue.detail().contains("animation \"ghost\" is not loaded"), issue.detail());
        assertEquals(Set.of("ghost/one"), DataCheck.skippedKeys(issues));
    }

    @Test
    void placementInAMissingWorldIsSkipped() {
        List<LoadIssue> issues = check(List.of(placement("demo", "one", "the_end")), Set.of());

        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("world \"the_end\" does not exist"),
                issues.getFirst().detail());
    }

    @Test
    void aMissingAnimationIsReportedOnceNotAlsoAsAWorldProblem() {
        // Both are wrong; one issue per placement keeps the report readable.
        List<LoadIssue> issues = check(List.of(placement("ghost", "one", "nowhere")), Set.of());
        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("not loaded"));
    }

    @Test
    void unknownGroupInTheConsultedListIsSkipped() {
        // hyphen: cannot be a player name, so it must be a group
        List<LoadIssue> issues = check(List.of(placement("demo", "one", "world",
                VisibilityMode.WHITELIST, Set.of("vip-team"), Set.of())), Set.of("staff"));

        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().detail().contains("visibility entry \"vip-team\""),
                issues.getFirst().detail());
        assertEquals("demo/one", issues.getFirst().subject());
    }

    @Test
    void aKnownGroupPasses() {
        assertEquals(List.of(), check(List.of(placement("demo", "one", "world",
                VisibilityMode.WHITELIST, Set.of("vip-team"), Set.of())), Set.of("vip-team")));
    }

    @Test
    void aPlainPlayerNamePassesWithoutBeingAGroup() {
        assertEquals(List.of(), check(List.of(placement("demo", "one", "world",
                VisibilityMode.WHITELIST, Set.of("Steve", "Player_123"), Set.of())), Set.of()));
    }

    @Test
    void onlyTheListTheModeConsultsIsChecked() {
        // A stale whitelist under blacklist visibility changes nothing on screen: not an issue.
        assertEquals(List.of(), check(List.of(placement("demo", "one", "world",
                VisibilityMode.BLACKLIST, Set.of("bad entry!"), Set.of())), Set.of()));

        // The same entry on the blacklist, which blacklist visibility does consult, is reported.
        assertEquals(1, check(List.of(placement("demo", "one", "world",
                VisibilityMode.BLACKLIST, Set.of(), Set.of("bad entry!"))), Set.of()).size());
    }

    @Test
    void eachPlacementIsJudgedByItsOwnLists() {
        // The lists are per placement: a bad entry on one says nothing about its sibling.
        List<LoadIssue> issues = check(List.of(
                placement("demo", "clean", "world", VisibilityMode.WHITELIST, Set.of("Steve"), Set.of()),
                placement("demo", "dirty", "world", VisibilityMode.WHITELIST, Set.of("bad entry!"), Set.of())),
                Set.of());

        assertEquals(Set.of("demo/dirty"), DataCheck.skippedKeys(issues));
    }

    @Test
    void everyoneAndNoneConsultNoLists() {
        for (VisibilityMode mode : List.of(VisibilityMode.EVERYONE, VisibilityMode.NONE)) {
            assertEquals(List.of(), check(List.of(placement("demo", "one", "world", mode,
                    Set.of("!!!"), Set.of("!!!"))), Set.of()), "mode " + mode);
        }
    }

    @Test
    void oneBadPlacementDoesNotSkipTheOthers() {
        List<Placement> placements = List.of(
                placement("demo", "good", "world"),
                placement("ghost", "bad", "world"),
                placement("spiral", "alsogood", "world_nether"),
                placement("demo", "badworld", "the_end"));

        List<LoadIssue> issues = check(placements, Set.of());

        // Exactly the two broken ones are skipped; the healthy pair is untouched.
        assertEquals(Set.of("ghost/bad", "demo/badworld"), DataCheck.skippedKeys(issues));
        assertEquals(2, issues.size());
    }

    @Test
    void emptyListsAreNotAnIssue() {
        // A whitelist placement nobody has been added to yet shows nothing, but is not broken.
        assertEquals(List.of(), check(
                List.of(placement("demo", "one", "world", VisibilityMode.WHITELIST)), Set.of()));
    }

    @Test
    void issuesRenderAShortLineWithDetailInTheHover() {
        LoadIssue issue = check(List.of(placement("ghost", "one", "world")), Set.of())
                .getFirst();

        assertTrue(issue.line().startsWith("<gray>[</gray><aqua>Billboard</aqua><gray>]</gray> "),
                issue.line());
        assertTrue(issue.line().contains("Skipped placement <white>ghost/one</white>"), issue.line());
        assertFalse(issue.line().contains("hover for details"), issue.line());
        assertTrue(issue.hover().contains("is not loaded"), issue.hover());
        assertTrue(issue.hover().contains("/billboard reload"), issue.hover());
        assertTrue(issue.plain().startsWith("Skipped placement \"ghost/one\": "), issue.plain());
    }

    @Test
    void untrustedNamesAreEscapedInTheReport() {
        // A placement id is user input, and it reaches the visible line as the subject.
        LoadIssue subject = check(List.of(placement("ghost", "<red>x", "world")), Set.of())
                .getFirst();
        assertTrue(subject.line().contains("ghost/\\<red>x"), subject.line());

        // A world name is user input too, and it reaches the hover through the detail.
        LoadIssue detail = check(List.of(placement("demo", "one", "<click>evil")), Set.of())
                .getFirst();
        assertTrue(detail.hover().contains("\\<click>evil"), detail.hover());
    }
}
