package dev.vexelray.maistro;

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * The window's side of the application: a {@code @View}, so everything here runs on the GUI thread, in each frame's
 * widget tick, which is the only place a widget may be built or written (vexelray-gui 0.2).
 *
 * <ul>
 *   <li><b>{@link Changed}</b>: the model moved, so the window is redrawn from {@link Model#doc()}.</li>
 *   <li><b>{@link Intent}</b>: a chord asks for something; the command says what.</li>
 *   <li><b>{@link Work}'s replies</b>: a job finished (a conversation written, listed or read, the sign-in checked),
 *       and the part that asked acts on it here, on the GUI thread.</li>
 *   <li><b>{@link WindowOpened}</b> for the main window: bring back last time's panes, start keeping conversations,
 *       ask Claude Code whether it is signed in, and put the keyboard in the composer. Until 0.2 this was a recipe
 *       that took the {@code Shell} so as to be built last, which the processor now refuses. It waits for the window
 *       because a tree built headless for a test ({@code UiTest}) must do none of it: restoring writes settings, the
 *       archive writes conversations, and the sign-in check runs a process. No window opens there, so the framework
 *       never says {@code WindowOpened}.</li>
 * </ul>
 */
@dev.vexelray.framework.api.View
final class Screen {

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final Actions actions;
    private final Session session;
    private final Archive archive;
    private final Conductor conductor;

    Screen(Gui gui, Keymap keys, Model model, Ui ui, Actions actions, Session session, Archive archive,
           Conductor conductor, Sender<Intent> intents) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.actions = actions;
        this.session = session;
        this.archive = archive;
        this.conductor = conductor;
        commands(keys, intents);
    }

    /**
     * Every command the keyboard reaches, as a declaration: a stable id, what the person calls it, the chords it starts
     * with, and the {@link Intent} it sends. The chords are the person's to change (the title bar's key button lists
     * these), and the id is what their changes are stored under, so it is the part to keep stable. Chords a text field
     * leaves alone, so a focused composer never needs to outrank them. Zoom is here too: which chord zooms is the
     * application's to say; how far it goes is {@code Appearance.ZoomRange}, the framework's.
     */
    private static void commands(Keymap keys, Sender<Intent> intents) {
        command(keys, intents, "pane.conversation", "Show or hide the conversation", Intent.Command.TOGGLE_CONVERSATION,
                Shortcut.of(Key.DIGIT_1, Modifier.CONTROL));
        command(keys, intents, "pane.map", "Show or hide the mind map", Intent.Command.TOGGLE_MAP,
                Shortcut.of(Key.DIGIT_2, Modifier.CONTROL));
        command(keys, intents, "pane.context", "Show or hide the context", Intent.Command.TOGGLE_CONTEXT,
                Shortcut.of(Key.DIGIT_3, Modifier.CONTROL));
        command(keys, intents, "go.composer", "Go to the composer", Intent.Command.FOCUS_COMPOSER,
                Shortcut.of(Key.L, Modifier.CONTROL));
        command(keys, intents, "agent.escape", "Close the list, or stop the agent", Intent.Command.ESCAPE,
                Shortcut.of(Key.ESCAPE));
        command(keys, intents, "conversation.new", "New conversation", Intent.Command.NEW_CONVERSATION,
                Shortcut.of(Key.N, Modifier.CONTROL));
        command(keys, intents, "conversation.open", "Saved conversations", Intent.Command.CONVERSATIONS,
                Shortcut.of(Key.O, Modifier.CONTROL));
        command(keys, intents, "conversation.branch", "Branch from the focused node", Intent.Command.BRANCH,
                Shortcut.of(Key.B, Modifier.CONTROL));
        command(keys, intents, "view.zoom-in", "Zoom in", Intent.Command.ZOOM_IN,
                Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL));
        command(keys, intents, "view.zoom-out", "Zoom out", Intent.Command.ZOOM_OUT,
                Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL));
        command(keys, intents, "view.zoom-reset", "Reset zoom", Intent.Command.ZOOM_RESET,
                Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL));
    }

    private static void command(Keymap keys, Sender<Intent> intents, String id, String label, Intent.Command command,
                                Shortcut... chords) {
        keys.action(id, label, chords).sends(intents, new Intent(command));
    }

    @Subscribe
    public void changed(Changed changed) {
        ui.show(model.doc());
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    // ------------------------------------------------------------------ what the jobs answer

    @Subscribe
    public void written(Work.Written written) {
        archive.written(written);
    }

    @Subscribe
    public void listed(Work.Listed listed) {
        actions.listed(listed);
    }

    @Subscribe
    public void loaded(Work.Loaded loaded) {
        actions.loaded(loaded);
    }

    @Subscribe
    public void accountChecked(Work.AccountChecked checked) {
        conductor.accountChecked(checked.check(), checked.account());
    }

    @Subscribe
    public void opened(WindowOpened opened) {
        if (!opened.main()) {
            return;
        }
        session.restore();
        archive.arm();
        actions.checkAccount();
        ui.conversation().focusComposer();
    }

    // ------------------------------------------------------------------ what the commands do

    Actions actions() {
        return actions;
    }

    void toggle(Doc.Pane pane) {
        model.toggle(pane);
    }

    void focusComposer() {
        ui.conversation().focusComposer();
    }

    /** In ({@code 1}), out ({@code -1}), or back to where it started ({@code 0}). */
    void zoom(int direction) {
        if (direction > 0) {
            gui.zoomIn();
        } else if (direction < 0) {
            gui.zoomOut();
        } else {
            gui.resetZoom();
        }
    }
}
