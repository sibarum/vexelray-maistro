package dev.vexelray.maistro;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The palette, as anchors, measured from the design canvas (docs/gui-design-guide.md, *Visual language*).
 *
 * <h2>Seven authored colours, five decisions</h2>
 *
 * <p>The canvas names a stage {@code #0F1316}, panels {@code #12181B}, rules {@code #222C31}, warm off-white text
 * {@code #E6E1D6}, amber {@code #F0A93B} for new / selected / primary, and teal {@code #5CC2B5} for settled /
 * structure. Measured in Oklab, the stage, the panel and the text sit on the construction {@link Palette} makes: the
 * stage is the page, the panel is two steps up the ladder, the text is the ink. Amber is the accent, because
 * everything the framework draws in its accent (focus, selection, the primary action) is what the canvas draws in
 * amber.
 *
 * <p>Two do not fit, and are roles of their own at their measured values rather than rounded into the ladder:
 *
 * <ul>
 *   <li>{@link #RULE}: the canvas's dividers are much stronger than its panels are lighter. A ladder step that puts
 *       the panel two levels up puts that rule ten levels up, past where the framework's {@code LINE} (level 4)
 *       sits. The widgets keep the ladder's line; the pane dividers use the canvas's.</li>
 *   <li>{@link #SETTLED}: teal is a second chromatic voice the palette has no slot for.</li>
 * </ul>
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** The stage, {@code #0F1316}: behind everything, and what the frame clears to. */
    private static final Oklab PAGE = Oklab.polar(0.1838, 0.0088, -119.57);

    /** Text, {@code #E6E1D6}: warm, so the dark ground does not read as blue-black. */
    private static final Oklab INK = Oklab.polar(0.9106, 0.0157, 86.43);

    /** Amber, {@code #F0A93B}: new, selected, the primary action. */
    private static final Oklab ACCENT = Oklab.polar(0.7849, 0.1459, 73.84);

    /** The fill of a filled control: the amber, deeper, so a button sits into the stage rather than glowing on it. */
    private static final Oklab ACTION = Oklab.polar(0.5600, 0.1150, 70.00);

    /** The accent's chroma taken round to red, so a confirmation dialog belongs to this palette. */
    private static final Oklab DANGER = Oklab.polar(0.6200, 0.1500, 25.00);

    /** Shadows: near-black, at the stage's hue. */
    private static final Oklab DEPTH = Oklab.polar(0.1100, 0.0080, -119.57);

    /** How far one surface is from the next: two steps from the stage is the canvas's panel, {@code #12181B}. */
    private static final double STEP = 0.0110;

    /** How fast the ink fades towards the page: text(1) is DIM, text(2) is FAINT. */
    private static final double FADE = 0.30;

    /** How dark a shadow is. */
    private static final double SHADOW_ALPHA = 0.60;

    static final Palette PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    /** Lit surfaces on, letterpress off: small mono readouts cannot spare the crispness letterpress spends. */
    static final Theme THEME = Theme.of(PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ---------------------------------------------------------------- roles of our own

    /** The canvas's dividers, {@code #222C31}, at their measured value. See the class comment for why not LINE. */
    static final Color RULE = Oklab.polar(0.2858, 0.0166, -130.32).toColor();

    /** Teal, {@code #5CC2B5}: settled, structure, links. A tool that ran, an edge in the map. */
    static final Color SETTLED = Oklab.polar(0.7496, 0.0974, -175.69).toColor();

    /**
     * Emphasis in Markdown, which the framework does not name: the teal, so it reads as a second voice beside the
     * amber's headings and links. The atlas has one weight, so emphasis is a colour rather than a bold face.
     */
    static final Color EMPHASIS = SETTLED;

    private Look() {
    }
}
