package com.jhuanglululu.billboard.runtime;

/**
 * The entity kinds an animation can spawn. The kind fixes which attribute imports are legal
 * for an id: a {@code set_text} on a block display is a guest bug and kills the animation
 * rather than being ignored.
 *
 * <p>{@link #interpolatesOnClient()} splits the two rendering worlds. Display entities carry
 * interpolation durations in their metadata, so the client smooths a change on its own. Armor
 * stands and item entities have no such slots: any {@code over_ticks > 0} on their position,
 * pose or yaw has to be tweened by the host, one packet per tick (see {@link EntityTweens}).
 */
public enum EntityKind {

    BLOCK_DISPLAY("block display", "block displays", true),
    ITEM_DISPLAY("item display", "item displays", true),
    TEXT_DISPLAY("text display", "text displays", true),
    ARMOR_STAND("armor stand", "armor stands", false),
    ITEM("item entity", "item entities", false);

    private final String label;
    private final String plural;
    private final boolean clientInterpolated;

    EntityKind(String label, String plural, boolean clientInterpolated) {
        this.label = label;
        this.plural = plural;
        this.clientInterpolated = clientInterpolated;
    }

    /** The name used in error messages ("set_text on a block display"). */
    public String label() {
        return label;
    }

    /** The plural name, for listing the kinds an op accepts. */
    public String plural() {
        return plural;
    }

    /** The label with its indefinite article, so messages read "an armor stand", not "a". */
    public String labelWithArticle() {
        return ("aeiou".indexOf(Character.toLowerCase(label.charAt(0))) >= 0 ? "an " : "a ") + label;
    }

    /** Whether the client interpolates this kind's transforms from metadata durations. */
    public boolean interpolatesOnClient() {
        return clientInterpolated;
    }

    /** The three display kinds; they share the transform ABI and the billboard-mode slot. */
    public boolean isDisplay() {
        return this == BLOCK_DISPLAY || this == ITEM_DISPLAY || this == TEXT_DISPLAY;
    }
}
