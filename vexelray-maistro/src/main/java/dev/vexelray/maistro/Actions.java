package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.archive.ConversationCodec;
import dev.vexelray.maistro.archive.Summary;
import sibarum.atchung.Sender;

/**
 * Every command Maistro has. The chords for them are {@link Screen}'s, declared in its keymap.
 *
 * <p>Commands arrive on the GUI thread: a chord, a press, a submitted composer, each heard in the frame. So nothing here
 * waits. Talking to the agent's process is the {@link Conductor}'s, which never blocks the caller on it, so a slow
 * start of Claude Code is a slow reply rather than a frozen window. Reading the disk and asking the CLI whether it is
 * signed in are jobs' ({@link Work}): this sends the request, and {@link Screen} hands the reply back to the method
 * here that acts on it, on the GUI thread again.
 */
final class Actions {

    private final Model model;
    private final Ui ui;
    private final Conductor conductor;
    private final Archive archive;
    private final Sender<Work.AccountCheck> checks;

    Actions(Model model, Ui ui, Conductor conductor, Archive archive, Sender<Work.AccountCheck> checks) {
        this.model = model;
        this.ui = ui;
        this.conductor = conductor;
        this.archive = archive;
        this.checks = checks;
        ui.conversation().onSend(conductor::send);
        ui.conversation().onAnswer(conductor::answer);
        ui.conversation().onAlways(conductor::answerAlways);
        ui.conversation().onResume(this::resume);
        ui.conversation().onStrands(conductor::show, this::merge, conductor::abandon);
        ui.context().onBranch(this::branch);
        ui.conversation().onCheckAccount(this::checkAccount);
    }

    /** Ask Claude Code whether it is signed in. At startup, and on Check again; the answer is {@link CheckAccount}'s. */
    void checkAccount() {
        checks.send(conductor.checkAccount());
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

    /** Open the list of saved conversations, or close it if it is open. Its rows are read by a job. */
    void conversations() {
        if (model.doc().library().open()) {
            closeConversations();
            return;
        }
        model.change(d -> d.withLibrary(l -> Doc.Library.OPENING));
        ui.conversation().focusLibrary();
        archive.list();
    }

    /** The saved conversations' rows were read. */
    void listed(Work.Listed listed) {
        model.change(d -> d.withLibrary(l -> l.loaded(listed.rows())));
    }

    private void closeConversations() {
        model.change(d -> d.withLibrary(l -> Doc.Library.CLOSED));
        ui.conversation().focusComposer();
    }

    /**
     * Carry on with saved conversation {@code s}: have it read, and switch to it when it has been ({@link #loaded}).
     * Picking the one already open just closes the list.
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
        archive.load(s.id());
    }

    /** A saved conversation was read: switch to it, or say why not. */
    void loaded(Work.Loaded loaded) {
        if (!loaded.problem().isEmpty()) {
            model.say(loaded.problem());
            ui.alert();
            return;
        }
        Conversation c;
        try {
            c = ConversationCodec.decode(loaded.json());
        } catch (IllegalArgumentException e) {
            model.say("Could not open that conversation: " + e.getMessage());
            ui.alert();
            return;
        }
        if (conductor.resume(c)) {
            ui.conversation().focusComposer();
        } else {
            ui.alert();
        }
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
