package dev.vexelray.maistro;

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces, the type scale, and the gutters.
 *
 * <h2>Type is rem; gutters are dp</h2>
 *
 * <p>That is the framework's rule and it decides what zoom does. {@code rem} grows with the user's zoom because it
 * is proportional to text; {@code dp} does not, because tripling the frame around content you zoomed in to read
 * means seeing less of it.
 *
 * <h2>The faces are the atlas's, not the canvas's</h2>
 *
 * <p>The canvas asks for IBM Plex Sans and Plex Mono. The framework's text atlas is baked at build time from the
 * fonts in {@code vexelray-text}, so face 0 and face 1 are whatever it shipped, a sans and a mono, and the roles
 * carry over: prose in the sans, readouts, labels and chips in the mono. A character the atlas lacks draws as the
 * missing-glyph box, so check the charset in {@code vexelray-text}'s pom before reaching for a symbol.
 */
final class Type {

    /** Face 0: the sans. Prose: what the human and the agent say. */
    static final int UI = 0;

    /** Face 1: the mono. Readouts, labels (uppercase, letter-spaced on the canvas), chips, tool output. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** The conversation's prose. */
    static final Length BODY = Length.rem(0.9375f);

    /** Tool output and readouts. */
    static final Length CODE = Length.rem(0.8125f);

    /** Section labels and turn headings: the mono, small and uppercase. */
    static final Length LABEL = Length.rem(0.6875f);

    // --------------------------------------------------------------- gutters

    /** A tight gap: between a heading and its body. */
    static final Length TIGHT = Length.dp(6f);

    /** Between turns, and inside a pane's edge. */
    static final Length GUTTER = Length.dp(16f);

    /** The canvas's minimum touch target. */
    static final Length TARGET = Length.dp(44f);

    /** An icon inside a touch target. */
    static final Length ICON = Length.dp(20f);

    // ------------------------------------------------------------------ panes

    /** The icon rail. The canvas's 72px. */
    static final Length RAIL = Length.dp(72f);

    /** The conversation, at most the canvas's 620px: type-relative, so it widens with the text in it. */
    static final Length CONVERSATION = Length.rem(38f);

    /** The context stream: the canvas's 290 to 340px, at its middle. */
    static final Length CONTEXT = Length.rem(19.5f);

    private Type() {
    }
}
