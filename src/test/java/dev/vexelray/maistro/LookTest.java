package dev.vexelray.maistro;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette, held to what a dark theme has to be true of.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Since the design canvas, it also <b>pins the gap
 * between the design and the construction</b> -- assert each authored colour against what the palette derives,
 * with the difference written down. A gap that is measured is a known quantity; a gap that is absorbed is a
 * surprise waiting for a review.
 */
class LookTest {

    @Test
    void theInkReadsAgainstThePage() {
        Color page = Look.THEME.color(Role.PAGE);
        Color ink = Look.THEME.color(Role.INK);
        assertTrue(luminance(ink) - luminance(page) > 0.5f,
                "primary text should be far lighter than the page it sits on");
    }

    @Test
    void surfacesClimbAwayFromThePage() {
        float page = luminance(Look.THEME.color(Role.PAGE));
        float panel = luminance(Look.THEME.color(Role.PANEL));
        float raised = luminance(Look.THEME.color(Role.RAISED));
        assertTrue(panel > page, "a panel should sit above the page");
        assertTrue(raised > panel, "a raised surface should sit above a panel");
    }

    @Test
    void textFadesInSteps() {
        float ink = luminance(Look.THEME.color(Role.INK));
        float dim = luminance(Look.THEME.color(Role.DIM));
        float faint = luminance(Look.THEME.color(Role.FAINT));
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @Test
    void theAccentIsNotTheInk() {
        assertNotEquals(Look.THEME.color(Role.INK), Look.THEME.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /** The gap between the canvas and the construction, pinned: the authored colours the ladder is meant to hit. */
    @Test
    void theLadderLandsOnTheCanvasColours() {
        assertNear(0x0F1316, Look.THEME.color(Role.PAGE), "the stage");
        assertNear(0x12181B, Look.THEME.color(Role.PANEL), "the panel");
        assertNear(0xE6E1D6, Look.THEME.color(Role.INK), "the text");
        assertNear(0xF0A93B, Look.THEME.color(Role.ACCENT), "the amber");
        assertNear(0x222C31, Look.RULE, "the rule");
        assertNear(0x5CC2B5, Look.SETTLED, "the teal");
    }

    /** Within two steps of 255 on every channel. */
    private static void assertNear(int rgb, Color c, String what) {
        float tolerance = 2.5f / 255f;
        assertEquals((rgb >> 16 & 255) / 255f, c.r(), tolerance, what + " (red)");
        assertEquals((rgb >> 8 & 255) / 255f, c.g(), tolerance, what + " (green)");
        assertEquals((rgb & 255) / 255f, c.b(), tolerance, what + " (blue)");
    }

    /** Rough perceived brightness -- enough to order two shades, which is all these assertions need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
