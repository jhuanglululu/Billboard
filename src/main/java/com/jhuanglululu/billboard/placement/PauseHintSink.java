package com.jhuanglululu.billboard.placement;

import com.jhuanglululu.billboard.data.Placement;

/**
 * Delivers the "this placement is paused" nudge to one player who walked into range of a paused
 * placement. The controller decides <em>when</em> (proximity, once per player and placement);
 * the implementation decides <em>whether</em> — only admins and config log-viewers are told —
 * which is why it reports back rather than returning void.
 */
@FunctionalInterface
public interface PauseHintSink {

    /**
     * Offers the nudge about {@code placement} to {@code viewer}.
     *
     * @param placement      the paused placement the viewer is standing near
     * @param viewer         the player in range
     * @param animationLevel true when the animation's flag holds it (resume names the animation),
     *                       false when only this placement's flag does (resume names the id)
     * @return whether the hint was actually sent; only then is it recorded as already given, so a
     *     player who was not eligible to hear it still can once they are
     */
    boolean hint(Placement placement, ViewerPosition viewer, boolean animationLevel);
}
