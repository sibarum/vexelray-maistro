package dev.vexelray.maistro;

import dev.vexelray.gui.core.app.Settings;

import java.util.Objects;

/**
 * What comes back next time: which panes were up.
 *
 * <p>Kept in the framework's one {@link Settings}, the same store that remembers where the window was, under keys of
 * this application's own. Written when that changes, read once, after the window exists.
 *
 * <p>The conversation itself is not remembered here. Claude Code keeps its own sessions on disk, and resuming one
 * is a later slice (docs/TODO.md).
 */
final class Session {

    static final String PANES = "session.panes";

    private final Settings settings;
    private final Model model;
    private volatile String last;
    /** Off until {@link #restore} has run: before that, a write would overwrite what is being restored. */
    private volatile boolean armed;

    Session(Settings settings, Model model) {
        this.settings = settings;
        this.model = model;
    }

    /** The panes as three letters, c, m and x, each upper case when shown. */
    static String encode(Doc.Panes p) {
        return (p.conversation() ? "C" : "c") + (p.map() ? "M" : "m") + (p.context() ? "X" : "x");
    }

    /** Back from {@link #encode}; anything malformed is every pane up. */
    static Doc.Panes decode(String s) {
        if (s == null || s.length() != 3) {
            return Doc.Panes.ALL;
        }
        return new Doc.Panes(s.charAt(0) == 'C', s.charAt(1) == 'M', s.charAt(2) == 'X');
    }

    /**
     * Write what is remembered if it differs from what was last written. Runs on the model's listener. Reads
     * {@link Model#doc()} rather than a listener's snapshot, which may be older than the last direct call.
     */
    synchronized void remember() {
        if (!armed) {
            return;
        }
        String now = encode(model.doc().panes());
        if (Objects.equals(now, last)) {
            return;
        }
        last = now;
        settings.putString(PANES, now).save();
    }

    /** Bring back the panes. */
    void restore() {
        Doc.Panes panes = decode(settings.getString(PANES, encode(Doc.Panes.ALL)));
        model.change(d -> d.withPanes(panes));
        armed = true;
        remember();
    }
}
