package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.widget.Cues;

/**
 * The application's one tempo, on the framework's clock.
 *
 * <h2>Motion is opt-in, so it is easy to lose</h2>
 *
 * <p>A widget animates only when it is handed a {@link Timing} — {@code Tabs.slide}, {@code TreeView.motion},
 * {@code Cues} — and without one it cuts. So leaving it out costs no error and no warning, only a stiffer window. A
 * timing is a tempo, stated as data; the window's clock runs it (KronoGui installs itself as the tree's clock when it
 * attaches), so the tempi are said here, in one place.
 *
 * <p>Two durations, and they differ on purpose. A change — a tab arriving, a folder opening — should be over before
 * Ctrl+Tab held down could want the next one. A cue has to be <em>noticed</em>, and once its attack and release
 * are taken out a cue as short as a change has too little visible motion left.
 *
 * <p>{@code settle} on the automation socket waits these out, so a script's photograph is of the window at rest.
 */
final class Motion {

    /** What a change is worth, in milliseconds. */
    static final long CHANGE = 160;

    /** What being seen costs, in milliseconds. */
    static final long CUE = 240;

    /** A change, linear: {@code Tabs.slide} eases its own travel, and a dissolve has no place to arrive at. */
    final Timing change = Timing.millis(CHANGE, Timing.Curve.LINEAR);

    /** A change that arrives somewhere — rows opening under a folder — eased into place. */
    final Timing arrival = Timing.millis(CHANGE, Timing.Curve.OUT_CUBIC);

    /** One-shot pictures over a node: this was already open, this did not work. */
    final Cues cues;

    Motion(Gui gui) {
        this.cues = new Cues(gui, Timing.millis(CUE, Timing.Curve.LINEAR));
    }
}
