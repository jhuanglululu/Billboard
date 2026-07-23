package com.jhuanglululu.billboard.load;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The difference between two scans of the animations folder, computed from per-file content
 * hashes: which animations were added, changed, or removed. Pure and unit-testable; the plugin
 * uses it on {@code /billboard reload} to stop instances of changed/removed animations.
 */
public record AnimationReloadDiff(Set<String> added, Set<String> changed, Set<String> removed) {

    public AnimationReloadDiff {
        added = new TreeSet<>(added);
        changed = new TreeSet<>(changed);
        removed = new TreeSet<>(removed);
    }

    /** Compare old vs new {@code (name -> content hash)} maps. */
    public static AnimationReloadDiff compute(Map<String, Integer> oldHashes,
            Map<String, Integer> newHashes) {
        Set<String> added = new TreeSet<>();
        Set<String> changed = new TreeSet<>();
        Set<String> removed = new TreeSet<>();
        for (Map.Entry<String, Integer> e : newHashes.entrySet()) {
            Integer old = oldHashes.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String name : oldHashes.keySet()) {
            if (!newHashes.containsKey(name)) {
                removed.add(name);
            }
        }
        return new AnimationReloadDiff(added, changed, removed);
    }

    /** Animations whose running instances must be stopped (changed or removed). */
    public Set<String> stopped() {
        Set<String> out = new TreeSet<>(changed);
        out.addAll(removed);
        return out;
    }
}
