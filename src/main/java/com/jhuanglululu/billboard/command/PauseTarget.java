package com.jhuanglululu.billboard.command;

import com.jhuanglululu.billboard.data.Placement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Resolves the single word {@code /billboard pause} and {@code /billboard resume} take into what
 * it addresses: an animation (the shared error-pause flag) or one placement (the per-placement
 * flag). Pure (no Bukkit), so the resolution order and the ambiguity rule are unit-testable.
 *
 * <p>Order is animation first, then placement id — an animation name is globally unique, a
 * placement id is only unique within its animation, so the unambiguous reading wins. A placement
 * id used under several animations is {@link Kind#AMBIGUOUS} rather than a guess; the caller
 * reports the {@code animation/id} candidates.
 *
 * @param kind       what the word turned out to address
 * @param animation  the animation, for {@link Kind#ANIMATION} and {@link Kind#PLACEMENT}
 * @param id         the placement id, for {@link Kind#PLACEMENT} only
 * @param candidates the {@code animation/id} keys that matched, for {@link Kind#AMBIGUOUS} only
 */
public record PauseTarget(Kind kind, String animation, String id, List<String> candidates) {

    /** What a pause/resume argument addressed. */
    public enum Kind {
        /** An animation: the per-animation flag, shared with the error pause. */
        ANIMATION,
        /** Exactly one placement across all animations: the per-placement flag. */
        PLACEMENT,
        /** A placement id carried by more than one animation. */
        AMBIGUOUS,
        /** Neither a known animation nor any placement's id. */
        UNKNOWN
    }

    /**
     * Resolves {@code word} against the known animations and every placement.
     *
     * @param word       the command argument
     * @param animations known animation names (loaded modules plus persisted settings entries)
     * @param placements every placement, of every animation
     */
    public static PauseTarget resolve(String word, Set<String> animations,
            Collection<Placement> placements) {
        if (animations.contains(word)) {
            return new PauseTarget(Kind.ANIMATION, word, null, List.of());
        }
        List<Placement> matches = new ArrayList<>();
        for (Placement p : placements) {
            if (p.id().equals(word)) {
                matches.add(p);
            }
        }
        if (matches.isEmpty()) {
            return new PauseTarget(Kind.UNKNOWN, null, null, List.of());
        }
        if (matches.size() == 1) {
            Placement p = matches.get(0);
            return new PauseTarget(Kind.PLACEMENT, p.animation(), p.id(), List.of());
        }
        List<String> keys = new ArrayList<>();
        for (Placement p : matches) {
            keys.add(p.key());
        }
        return new PauseTarget(Kind.AMBIGUOUS, null, word, List.copyOf(keys));
    }
}
