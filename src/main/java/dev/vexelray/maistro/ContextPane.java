package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.maistro.map.Edge;
import dev.vexelray.maistro.map.MapGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The context stream: the machinery made visible.
 *
 * <p>The canvas puts four things here: the focus, the entropic KV cache, the vector index's nearest hits and the
 * session graph's counts. Only what Maistro actually knows is shown as a value. Today that is the agent's own
 * session (model, folder, turns, cost, tokens), read from Claude Code's init and result lines. The other sections
 * are named, and say they are not connected, rather than drawing numbers nothing produced.
 */
final class ContextPane {

    private final Gui gui;
    private final Node root;
    private final Node model;
    private final Node session;
    private final Node folder;
    private final Node turns;
    private final Node cost;
    private final Node tokens;
    private final Node focus;
    private final Node links;
    private final Node branch;
    private final Node all;
    private final Node allRow;

    private volatile Runnable branchAction = () -> { };

    ContextPane(Gui gui) {
        this.gui = gui;
        focus = gui.text("").font(Type.UI).textSize(Type.BODY).textColor(gui.theme().color(Role.INK))
                .wordWrap(true).width(Length.FILL);
        gui.landmark(Landmarks.CONTEXT_FOCUS, focus);
        links = note("");
        Button branchButton = new Button(gui, "Branch from here (Ctrl+B)").kind(Button.Kind.SECONDARY)
                .onPress(() -> branchAction.run());
        gui.landmark(Landmarks.CONTEXT_BRANCH, branchButton.node());
        this.branch = branchButton.node();
        all = value();
        model = value();
        session = value();
        folder = value();
        turns = value();
        cost = value();
        tokens = value();

        Node sessionSection = section("SESSION",
                readout("model", model),
                readout("session", session),
                readout("folder", folder),
                readout("turns", turns),
                readout("cost", cost),
                readout("tokens", tokens),
                allRow = readout("all strands", all));
        gui.landmark(Landmarks.CONTEXT_SESSION, sessionSection);

        root = gui.column()
                .width(Type.CONTEXT).height(Length.FILL)
                .background(gui.theme().color(Role.PANEL))
                .border(Length.dp(1), Look.RULE)
                .alignItems(AlignItems.STRETCH)
                .scroll(false, true)
                .children(ConversationPane.header(gui, "CONTEXT STREAM"),
                        section("FOCUS", focus, links, this.branch),
                        sessionSection,
                        section("KV CACHE", note("Not connected yet.")),
                        section("VECTOR INDEX", note("Not connected yet.")),
                        section("SESSION GRAPH", note("Not connected yet.")));
        gui.landmark(Landmarks.CONTEXT, root);
        show(Doc.AgentInfo.NONE);
        show(MapGraph.EMPTY);
        showTotals(Doc.AgentInfo.NONE, 1);
    }

    Node node() {
        return root;
    }

    /** Write the session's readouts. */
    void show(Doc.AgentInfo info) {
        model.text(orDash(info.model()));
        session.text(info.sessionId().isEmpty() ? "-" : shortId(info.sessionId()));
        folder.text(tail(info.cwd()));
        turns.text(Integer.toString(info.turns()));
        cost.text(String.format(Locale.ROOT, "$%.4f", info.costUsd()));
        tokens.text(info.inputTokens() + " in / " + info.outputTokens() + " out");
    }

    /** What "Branch from here" does. */
    void onBranch(Runnable action) {
        this.branchAction = action;
    }

    /** The cost of every strand together; shown only when there is more than the main one. */
    void showTotals(Doc.AgentInfo totals, int strands) {
        all.text(String.format(Locale.ROOT, "$%.4f in %d strands", totals.costUsd(), strands));
        allRow.visible(strands > 1);
    }

    /** Write the focus: the focused node's label and what it links to, or how to focus one. */
    void show(MapGraph graph) {
        dev.vexelray.maistro.map.Node node = graph.focus().flatMap(graph::node).orElse(null);
        if (node == null) {
            focus.visible(false).text("");
            branch.visible(false);
            links.text("Click a node in the map to focus it.");
            return;
        }
        focus.visible(true).text(node.shownLabel());
        branch.visible(true);
        links.text(linkedTo(graph, node.id()));
    }

    /** {@code linked to KV cache, Eviction}: the labels of the nodes one edge from {@code id}. */
    static String linkedTo(MapGraph graph, String id) {
        List<String> names = new ArrayList<>();
        for (Edge e : graph.edges()) {
            if (e.touches(id)) {
                graph.node(e.other(id)).ifPresent(n -> names.add(n.shownLabel()));
            }
        }
        return names.isEmpty() ? "linked to nothing yet" : "linked to " + String.join(", ", names);
    }

    private Node section(String label, Node... rows) {
        Node body = gui.column().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.STRETCH).children(rows);
        return gui.column()
                .width(Length.FILL)
                .padding(Type.TIGHT, Type.GUTTER)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(gui.text(label).font(Type.MONO).textSize(Type.LABEL)
                        .textColor(gui.theme().color(Role.DIM)), body);
    }

    private Node readout(String label, Node value) {
        return gui.row()
                .width(Length.FILL)
                .justify(Justify.SPACE_BETWEEN)
                .gap(Type.TIGHT)
                .children(gui.text(label).font(Type.MONO).textSize(Type.CODE)
                        .textColor(gui.theme().color(Role.FAINT)), value);
    }

    private Node value() {
        return gui.text("-").font(Type.MONO).textSize(Type.CODE).textColor(gui.theme().color(Role.INK));
    }

    private Node note(String text) {
        return gui.text(text).font(Type.UI).textSize(Type.CODE).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(true).width(Length.FILL);
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    /** The last two parts of a path: enough to tell which project, and short enough for the column. */
    static String tail(String path) {
        if (path == null || path.isEmpty()) {
            return "-";
        }
        String p = path.replace('\\', '/');
        int last = p.lastIndexOf('/', p.length() - 2);
        int before = last > 0 ? p.lastIndexOf('/', last - 1) : -1;
        return before >= 0 ? "…" + p.substring(before) : p;
    }

    /** A session id is a UUID; its first group is enough to tell two apart at a glance. */
    private static String shortId(String id) {
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : id;
    }
}
