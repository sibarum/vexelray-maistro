package dev.vexelray.maistro;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window, left to right as the design canvas lays it out: the icon rail, the conversation, the mind map (the
 * main stage, taking what is left), and the context stream; the framework's title bar over them and a status line
 * under them.
 *
 * <h2>It holds no state</h2>
 *
 * <p>{@link #show} takes a whole {@link Doc} and writes everything derived from it, which is the rule worth keeping
 * as this grows: a label that remembers a value can disagree with the session.
 *
 * <p>{@code show} is called from the committing thread, which is a worker or the agent's reader. That is correct and
 * is the framework's own idiom: a prop written off the GUI thread is queued and applied by the next drain. What is
 * <em>not</em> allowed is the other direction, reading the model from inside the frame loop.
 */
final class Ui {

    private final TitleBar titleBar;
    private final StatusBar status;
    private final PaneRail rail;
    private final ConversationPane conversation;
    private final MapPane map;
    private final ContextPane context;
    private final Motion motion;
    private final Color danger;

    Ui(Gui gui, Motion motion, Model model, TitleBar titleBar) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_AGENT, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_MODEL, StatusBar.Side.RIGHT, "")
                .minWidth(Landmarks.STATUS_AGENT, Length.rem(9));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_AGENT, Landmarks.STATUS_MESSAGE, Landmarks.STATUS_MODEL}) {
            gui.landmark(slot, status.slot(slot));
        }

        rail = new PaneRail(gui, model::toggle);
        conversation = new ConversationPane(gui, model::unanchor);
        map = new MapPane(gui, model::pick);
        context = new ContextPane(gui);

        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .alignItems(AlignItems.STRETCH)
                .children(rail.node(), conversation.node(), map.node(), context.node());

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body, status.node());
        show(model.doc());
    }

    ConversationPane conversation() {
        return conversation;
    }

    /** Draw the eye to the status line: something was refused or failed, and the message there says what. */
    void alert() {
        motion.cues.play(status.node(), Cue.ring(danger, 2));
    }

    /** Write everything derived from the session. One method taking the whole value, so no two fields can disagree. */
    void show(Doc doc) {
        titleBar.title(Maistro.TITLE);
        rail.show(doc.panes());
        conversation.node().visible(doc.panes().conversation());
        map.node().visible(doc.panes().map());
        context.node().visible(doc.panes().context());
        conversation.show(doc.transcript());
        conversation.showAnchor(doc.mind().anchorNode().map(dev.vexelray.maistro.map.Node::shownLabel).orElse(""));
        context.show(doc.agent());
        context.show(doc.mind().graph());
        map.show(doc.mind());
        status.text(Landmarks.STATUS_AGENT, "agent: " + doc.activity());
        status.text(Landmarks.STATUS_MESSAGE, doc.status());
        status.text(Landmarks.STATUS_MODEL, doc.agent().model());
    }
}
