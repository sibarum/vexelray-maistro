package dev.vexelray.maistro;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.MapProtocol;
import dev.vexelray.maistro.text.Markdown;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The conversation: the transcript, tailing as it grows, over the composer.
 *
 * <h2>One view per turn, kept</h2>
 *
 * <p>A turn's nodes are built the first time it is shown and kept, keyed by the turn's id, so a reply streaming in
 * rewrites one text node per delta rather than rebuilding the transcript. The transcript itself is the model's;
 * this holds widgets, never the conversation.
 *
 * <h2>The composer</h2>
 *
 * <p>A single-line field for now: Enter sends. The canvas's predictive composer (ghost text, Tab to accept) and the
 * daydreaming candidates under it are later slices; see docs/TODO.md.
 */
final class ConversationPane {

    private final Gui gui;
    private final Node transcript;
    private final Node root;
    private final TextField composer;
    private final Markdown.Style markup;
    private final Map<Long, TurnView> views = new HashMap<>();
    private final Node empty;
    /** Each speaker's heading colour: the human in ink, the agent in amber, a tool in teal, a note faint. */
    private final Map<Turn.Who, Color> headings = new EnumMap<>(Turn.Who.class);

    /** Over the composer while the next message starts from a node: which node, and a button to let it go. */
    private final Node from;
    private final Node fromLabel;

    private volatile Consumer<String> send = text -> { };
    private volatile Answer answer = (turn, allow) -> { };

    /** What pressing Allow or Deny under a tool turn does. */
    interface Answer {
        void answer(long turn, boolean allow);
    }

    /** The conversation; {@code unanchor} is pressing the ✕ on the {@code from} chip. */
    ConversationPane(Gui gui, Runnable unanchor) {
        this.gui = gui;
        this.markup = new Markdown.Style(
                gui.theme().color(Role.ACCENT),
                gui.theme().color(Role.FAINT),
                Look.EMPHASIS,
                gui.theme().color(Role.ACCENT),
                gui.theme().color(Role.DIM),
                gui.theme().color(Role.INK),
                gui.theme().color(Role.RAISED));

        headings.put(Turn.Who.HUMAN, gui.theme().color(Role.DIM));
        headings.put(Turn.Who.AGENT, gui.theme().color(Role.ACCENT));
        headings.put(Turn.Who.TOOL, Look.SETTLED);
        headings.put(Turn.Who.NOTE, gui.theme().color(Role.FAINT));

        empty = gui.text("Say what you want done. Maistro runs Claude Code, which can drive the VexelRay apps.")
                .font(Type.UI).textSize(Type.BODY).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(true).width(Length.FILL);

        transcript = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .padding(Type.GUTTER, Type.GUTTER)
                .gap(Type.GUTTER)
                .alignItems(AlignItems.STRETCH)
                .scroll(false, true)
                .scrollLock(ScrollLock.BOTTOM)
                .children(empty);
        gui.landmark(Landmarks.TRANSCRIPT, transcript);

        composer = new TextField(gui, "").onSubmit(this::submit);
        composer.node().width(Length.grow(1f)).font(Type.UI).textSize(Type.BODY);
        gui.landmark(Landmarks.COMPOSER, composer.node());

        IconButton sendButton = new IconButton(gui, Icon.ARROW_UP, "Send")
                .size(Type.TARGET, Type.ICON)
                .tip("Send (Enter)")
                .onPress(() -> submit(composer.text()));
        gui.landmark(Landmarks.SEND, sendButton.node());

        Node composerRow = gui.row()
                .width(Length.FILL)
                .padding(Type.TIGHT, Type.GUTTER)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER)
                .border(Length.dp(1), Look.RULE)
                .children(composer.node(), sendButton.node());

        fromLabel = gui.text("").font(Type.MONO).textSize(Type.LABEL).textColor(gui.theme().color(Role.ACCENT));
        gui.landmark(Landmarks.COMPOSER_FROM, fromLabel);
        IconButton clear = new IconButton(gui, Icon.CLOSE, "Start from nowhere in particular")
                .size(Type.TARGET, Type.ICON)
                .tip("Start from nowhere in particular")
                .onPress(unanchor);
        gui.landmark(Landmarks.COMPOSER_FROM_CLEAR, clear.node());
        from = gui.row()
                .width(Length.FILL)
                .padding(Length.dp(0), Type.GUTTER)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER)
                .children(fromLabel, clear.node())
                .visible(false);

        root = gui.column()
                .width(Type.CONVERSATION).height(Length.FILL)
                .background(gui.theme().color(Role.PANEL))
                .border(Length.dp(1), Look.RULE)
                .alignItems(AlignItems.STRETCH)
                .children(header(gui, "CONVERSATION"), transcript, from, composerRow);
        gui.landmark(Landmarks.CONVERSATION, root);
    }

    /** A pane's label: the mono, small, uppercase and faint, as the canvas heads every pane. */
    static Node header(Gui gui, String label) {
        return gui.row()
                .width(Length.FILL)
                .padding(Type.TIGHT, Type.GUTTER)
                .children(gui.text(label).font(Type.MONO).textSize(Type.LABEL)
                        .textColor(gui.theme().color(Role.FAINT)));
    }

    Node node() {
        return root;
    }

    /** What submitting the composer does. Handed the text, never empty. */
    void onSend(Consumer<String> handler) {
        this.send = handler;
    }

    /** What Allow and Deny under a waiting tool turn do. */
    void onAnswer(Answer handler) {
        this.answer = handler;
    }

    /** Say which node the next message starts from: its label, or empty for none. */
    void showAnchor(String label) {
        fromLabel.text(label.isEmpty() ? "" : "from · " + label);
        from.visible(!label.isEmpty());
    }

    /** Put the keyboard in the composer. */
    void focusComposer() {
        gui.focus(composer.node());
    }

    private void submit(String text) {
        String message = text.strip();
        if (message.isEmpty()) {
            return;
        }
        composer.text("");
        send.accept(message);
    }

    /**
     * Bring the widgets up to {@code t}: build a view for each turn not seen before, rewrite the ones that changed.
     * Called from the model's listener, whose deliveries are serialised; synchronized anyway, since a test may call
     * it directly.
     */
    synchronized void show(Transcript t) {
        empty.visible(t.size() == 0);
        // A new conversation drops every turn, and its ids start again: views of turns that are gone go first.
        java.util.Set<Long> present = new java.util.HashSet<>();
        for (Turn turn : t.turns()) {
            present.add(turn.id());
        }
        for (java.util.Iterator<Map.Entry<Long, TurnView>> it = views.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, TurnView> e = it.next();
            if (!present.contains(e.getKey())) {
                e.getValue().forget();
                it.remove();
            }
        }
        boolean grew = false;
        for (Turn turn : t.turns()) {
            TurnView view = views.get(turn.id());
            if (view == null) {
                view = new TurnView(turn);
                views.put(turn.id(), view);
                transcript.append(view.root);
                grew = true;
            } else {
                view.update(turn);
            }
        }
        if (grew) {
            transcript.scrollToEdge();
        }
        List<Turn> asking = t.asking();
        if (asking.isEmpty()) {
            gui.clearLandmark(Landmarks.ASK_ALLOW);
            gui.clearLandmark(Landmarks.ASK_DENY);
        } else {
            TurnView oldest = views.get(asking.getFirst().id());
            gui.landmark(Landmarks.ASK_ALLOW, oldest.allow.node());
            gui.landmark(Landmarks.ASK_DENY, oldest.deny.node());
        }
    }

    /** One turn's nodes: its heading, its body, and the chips saying what it touched. */
    private final class TurnView {

        final Node root;
        final Node heading;
        final Node body;
        final Node chips;
        /** Allow and Deny, shown while the tool waits on the human. */
        final Node ask;
        final Button allow;
        final Button deny;
        Turn shown;
        /** The chips on show, and what they say. */
        Node[] chipList = new Node[0];
        List<String> chipLabels = List.of();

        TurnView(Turn turn) {
            heading = gui.text("").font(Type.MONO).textSize(Type.LABEL);
            body = gui.text("").wordWrap(true).width(Length.FILL);
            chips = gui.row().gap(Type.TIGHT);
            long id = turn.id();
            allow = new Button(gui, "Allow").kind(Button.Kind.PRIMARY).onPress(() -> answer.answer(id, true));
            deny = new Button(gui, "Deny").kind(Button.Kind.SECONDARY).onPress(() -> answer.answer(id, false));
            gui.landmark(Landmarks.askAllow(id), allow.node());
            gui.landmark(Landmarks.askDeny(id), deny.node());
            ask = gui.row().gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                    .children(allow.node(), deny.node())
                    .visible(false);
            root = gui.column().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.STRETCH)
                    .children(heading, body, ask, chips);
            update(turn);
        }

        /** Take this turn out of the window, with the landmarks of its buttons. */
        void forget() {
            gui.clearLandmark(Landmarks.askAllow(shown.id()));
            gui.clearLandmark(Landmarks.askDeny(shown.id()));
            root.remove();
        }

        void update(Turn turn) {
            if (turn.equals(shown)) {
                return;
            }
            shown = turn;
            heading.text(turn.heading() + (turn.live() ? "  …" : "")).textColor(headingColour(turn));
            boolean prose = turn.who() == Turn.Who.HUMAN || turn.who() == Turn.Who.AGENT;
            // A reply still streaming may be part-way into its map block; the finished one has it stripped.
            String text = turn.who() == Turn.Who.AGENT && turn.live()
                    ? MapProtocol.parse(turn.text()).prose()
                    : turn.text();
            body.font(prose ? Type.UI : Type.MONO)
                    .textSize(prose ? Type.BODY : Type.CODE)
                    .textColor(gui.theme().color(prose ? Role.INK : Role.DIM))
                    .text(text);
            body.visible(!text.isEmpty());
            if (turn.who() == Turn.Who.AGENT) {
                body.spans(Markdown.spans(text, markup));
            }
            ask.visible(turn.asking());
            List<String> labels = new ArrayList<>(turn.chips());
            if (!turn.ask().chip().isEmpty()) {
                labels.add(turn.ask().chip());
            }
            if (!labels.equals(chipLabels)) {
                // children(...) appends, so the old chips are taken out first.
                for (Node old : chipList) {
                    old.remove();
                }
                chipList = chipNodes(labels);
                chipLabels = labels;
                chips.children(chipList);
            }
            chips.visible(!labels.isEmpty());
        }

        private Node[] chipNodes(List<String> labels) {
            Node[] nodes = new Node[labels.size()];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = gui.row()
                        .padding(Length.dp(2), Length.dp(8))
                        .corner(Length.dp(4))
                        .border(Length.dp(1), Look.RULE)
                        .direction(Direction.ROW)
                        .children(gui.text(labels.get(i)).font(Type.MONO).textSize(Type.LABEL)
                                .textColor(Look.SETTLED));
            }
            return nodes;
        }

        private Color headingColour(Turn turn) {
            if (turn.failed()) {
                return gui.theme().color(Role.DANGER);
            }
            return headings.get(turn.who());
        }
    }
}
