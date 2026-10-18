package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * Every command Maistro has, and the chords for them.
 *
 * <p>Commands arrive on workers: a shortcut, a press, a submitted composer. Talking to the agent's process is the
 * {@link Conductor}'s, which never blocks the caller on it, so a slow start of Claude Code is a slow reply rather
 * than a frozen window.
 */
final class Actions {

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final Conductor conductor;

    Actions(Gui gui, Model model, Ui ui, Conductor conductor) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.conductor = conductor;
        ui.conversation().onSend(conductor::send);
        ui.conversation().onAnswer(conductor::answer);
    }

    /**
     * Every chord, as a {@code GLOBAL} claim. Claims rather than key handlers, which is how this framework does
     * preemption: a focused field outranks a global claim by claiming the same chord itself, so the chords chosen
     * here are ones a text field leaves alone.
     */
    void shortcuts() {
        gui.shortcut(Key.DIGIT_1, () -> model.toggle(Doc.Pane.CONVERSATION), Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_2, () -> model.toggle(Doc.Pane.MAP), Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_3, () -> model.toggle(Doc.Pane.CONTEXT), Modifier.CONTROL);
        gui.shortcut(Key.L, ui.conversation()::focusComposer, Modifier.CONTROL);
        gui.shortcut(Key.ESCAPE, this::interrupt);
    }

    /** Stop the agent's turn, if it has one. */
    void interrupt() {
        if (model.doc().busy()) {
            conductor.interrupt();
        }
    }
}
