package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.data.Placement;
import java.util.Collection;
import java.util.Set;

/**
 * What {@code /billboard env <target>} and {@code /billboard restart <target>} name: either a whole
 * animation ({@code demo}) or one of its placements ({@code demo/lobby}).
 *
 * <p>Deliberately <b>not</b> {@link PauseTarget}'s resolution. Pause accepts a bare placement id and
 * searches every animation for it, which is convenient but ambiguous — and it has an
 * {@code AMBIGUOUS} outcome to prove it. Env layers are hierarchical, so the qualified
 * {@code animation/id} form is the honest spelling: the slash is what tells the two layers apart,
 * and there is nothing to disambiguate. A word with no slash is an animation, full stop.
 *
 * <p>Pure and unit-testable: it takes the known names and the placement list, not a data store.
 *
 * @param kind      what the word turned out to be
 * @param animation the animation name (set for both resolved kinds)
 * @param id        the placement id, or {@code null} for the animation form
 */
public record EnvTarget(Kind kind, String animation, String id) {

    /** What a target word resolved to. */
    public enum Kind {
        /** {@code <animation>} — the animation-level layer, shared by all its placements. */
        ANIMATION,
        /** {@code <animation>/<id>} — one placement's own layer. */
        PLACEMENT,
        /** Neither: no such animation, or no such placement of it. */
        UNKNOWN
    }

    /**
     * Resolves {@code token}.
     *
     * @param known      every animation name that exists (loaded modules plus persisted settings)
     * @param placements every placement, for checking the qualified form
     */
    public static EnvTarget resolve(String token, Set<String> known, Collection<Placement> placements) {
        int slash = token.indexOf('/');
        if (slash < 0) {
            boolean exists = known.contains(token)
                    || placements.stream().anyMatch(p -> p.animation().equals(token));
            return exists ? new EnvTarget(Kind.ANIMATION, token, null) : unknown();
        }
        String animation = token.substring(0, slash);
        String id = token.substring(slash + 1);
        boolean exists = placements.stream()
                .anyMatch(p -> p.animation().equals(animation) && p.id().equals(id));
        return exists ? new EnvTarget(Kind.PLACEMENT, animation, id) : unknown();
    }

    private static EnvTarget unknown() {
        return new EnvTarget(Kind.UNKNOWN, null, null);
    }

    /** The word this target would be typed as — what every message about it prints. */
    public String label() {
        return id == null ? animation : animation + "/" + id;
    }
}
