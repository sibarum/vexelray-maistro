package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.maistro.archive.Summary;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * Every command Maistro has, and the chords for them.
 *
 * <p>Commands arrive on workers: a shortcut, a press, a submitted composer. Talking to the agent's process is the
 * {@link Conductor}'s, which never blocks the caller on it, so a slow start of Claude Code is a slow reply rather
 * than a frozen window. Reading a saved conversation is the {@link Archive}'s, on the offload lane.
 */
final class Actions {

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final Conductor conductor;
    private final Archive archive;

    Actions(Gui gui, Model model, Ui ui, Conductor conductor, Archive archive) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.conductor = conductor;
        this.archive = archive;
        ui.conversation().onSend(conductor::send);
        ui.conversation().onAnswer(conductor::answer);
        ui.conversation().onAlways(conductor::answerAlways);
        ui.conversation().onResume(this::resume);
        ui.conversation().onStrands(conductor::show, this::merge, conductor::abandon);
        ui.context().onBranch(this::branch);
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
        gui.shortcut(Key.ESCAPE, this::escape);
        gui.shortcut(Key.N, this::newConversation, Modifier.CONTROL);
        gui.shortcut(Key.O, this::conversations, Modifier.CONTROL);
        gui.shortcut(Key.B, this::branch, Modifier.CONTROL);
    }

    /** Merge branch {@code id} into the main strand, or say why not. */
    void merge(String id) {
        if (!conductor.merge(id)) {
            ui.alert();
        }
    }

    /** Start a branch from the focused node, and put the keyboard in the composer to say its first thing. */
    void branch() {
        if (conductor.branch().isEmpty()) {
            ui.alert();
        } else {
            ui.conversation().focusComposer();
        }
    }

    /** Start a new conversation, unless the agent is in the middle of a turn; then say why not. */
    void newConversation() {
        if (conductor.restart()) {
            ui.conversation().focusComposer();
        } else {
            ui.alert();
        }
    }

    /** Open the list of saved conversations, or close it if it is open. Its rows are read on the offload lane. */
    void conversations() {
        if (model.doc().library().open()) {
            closeConversations();
            return;
        }
        model.change(d -> d.withLibrary(l -> Doc.Library.OPENING));
        ui.conversation().focusLibrary();
        archive.list(rows -> model.change(d -> d.withLibrary(l -> l.loaded(rows))));
    }

    private void closeConversations() {
        model.change(d -> d.withLibrary(l -> Doc.Library.CLOSED));
        ui.conversation().focusComposer();
    }

    /**
     * Carry on with saved conversation {@code s}: read it on the offload lane, then switch to it. Picking the one
     * already open just closes the list.
     */
    void resume(Summary s) {
        if (s.id().equals(model.doc().meta().id())) {
            closeConversations();
            return;
        }
        if (model.doc().anyBusy()) {
            model.say("The agent is working. Interrupt it (Escape) or wait for the reply, then switch.");
            ui.alert();
            return;
        }
        archive.load(s.id(), c -> {
            if (conductor.resume(c)) {
                ui.conversation().focusComposer();
            } else {
                ui.alert();
            }
        }, why -> {
            model.say(why);
            ui.alert();
        });
    }

    /** Escape: close the list of conversations if it is open, else stop the agent's turn if it has one. */
    void escape() {
        if (model.doc().library().open()) {
            closeConversations();
        } else if (model.doc().busy()) {
            conductor.interrupt();
        }
    }
}
