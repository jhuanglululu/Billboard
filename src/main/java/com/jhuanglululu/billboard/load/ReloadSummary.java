package com.jhuanglululu.billboard.load;

import java.util.List;

/**
 * The result of a {@code /billboard reload}: the {@link AnimationReloadDiff} plus any per-file
 * parse errors (each a human-readable line).
 */
public record ReloadSummary(AnimationReloadDiff diff, List<String> errors) {

    public ReloadSummary {
        errors = List.copyOf(errors);
    }
}
