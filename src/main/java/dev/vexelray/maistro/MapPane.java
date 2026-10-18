package dev.vexelray.maistro;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.maistro.map.Edge;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;

import java.util.HashSet;
import java.util.Set;

/**
 * The mind map: the main stage, a concept graph that grows with the conversation.
 *
 * <h2>A picture on a box</h2>
 *
 * <p>The graph and where each node sits are the session's ({@link Doc.Mind}); this only draws them, as one
 * {@code Picture} on the stage: edges in teal, nodes as labelled boxes, the last turn's nodes outlined in amber
 * ("from last turn"), and the focus filled in amber. The layout's units are scaled to fit the stage, no larger than
 * life, and centred.
 *
 * <p>A picture is in the box's pixels, so it is rebuilt when the stage is resized ({@code onResizeUi}, the same
 * frame as the layout) and when the mind changes. Both arrive on different threads, so the last of each is kept
 * and the drawing is synchronized.
 *
 * <p>Clicking a node to focus it is a later slice: the picture is not hit-tested (docs/TODO.md).
 */
final class MapPane {

    /** The label's size in layout units, at a scale of one: matches {@link Doc.Mind#CHAR}'s estimate. */
    private static final double LABEL = 13;

    private final Node root;
    private final Node stage;
    private final Node hint;
    private final Color ink;
    private final Color dim;
    private final Color box;
    private final Color amber;
    private final Color onAmber;

    private Doc.Mind mind = Doc.Mind.EMPTY;
    private float width;
    private float height;

    MapPane(Gui gui) {
        ink = gui.theme().color(Role.INK);
        dim = gui.theme().color(Role.DIM);
        box = gui.theme().color(Role.RAISED);
        amber = gui.theme().color(Role.ACCENT);
        onAmber = gui.theme().color(Role.PAGE);

        hint = gui.text("The map grows as you talk: each reply adds the ideas it touched.")
                .font(Type.UI).textSize(Type.BODY).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(true).width(Length.FILL);
        stage = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .padding(Type.GUTTER, Type.GUTTER)
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .clip(true)
                .children(hint);
        gui.onResizeUi(stage, this::resized);
        root = gui.column()
                .width(Length.grow(1f)).height(Length.FILL)
                .background(gui.theme().color(Role.PAGE))
                .alignItems(AlignItems.STRETCH)
                .children(ConversationPane.header(gui, "MIND MAP"), stage);
        gui.landmark(Landmarks.MAP, root);
    }

    Node node() {
        return root;
    }

    /** Draw {@code next}, if it is not what is already drawn. */
    synchronized void show(Doc.Mind next) {
        if (next.equals(mind)) {
            return;
        }
        mind = next;
        hint.visible(next.graph().isEmpty());
        draw();
    }

    private synchronized void resized(NodeLayout layout) {
        if (layout.rect().w() == width && layout.rect().h() == height) {
            return;
        }
        width = layout.rect().w();
        height = layout.rect().h();
        draw();
    }

    private void draw() {
        MapGraph graph = mind.graph();
        if (graph.isEmpty() || width <= 0 || height <= 0) {
            stage.picture(new Sketch().picture());
            return;
        }
        Layout.Box fit = Layout.fit(mind.positions(), graph, Doc.Mind::size);
        double scale = Math.min(1.25, fit.scaleToFit(width, height, 32));
        double cx = width / 2.0 - fit.centre().x() * scale;
        double cy = height / 2.0 - fit.centre().y() * scale;

        Set<String> fresh = new HashSet<>();
        for (dev.vexelray.maistro.map.Node n : graph.addedInLatestTurn()) {
            fresh.add(n.id());
        }
        String focus = graph.focus().orElse("");

        Sketch sketch = new Sketch().tag("edge");
        for (Edge e : graph.edges()) {
            Layout.Point a = mind.positions().get(e.from());
            Layout.Point b = mind.positions().get(e.to());
            if (a != null && b != null) {
                sketch.line(cx + a.x() * scale, cy + a.y() * scale, cx + b.x() * scale, cy + b.y() * scale,
                        Math.max(1, 1.5 * scale), Look.SETTLED);
            }
        }
        for (dev.vexelray.maistro.map.Node n : graph.nodes()) {
            Layout.Point p = mind.positions().get(n.id());
            if (p == null) {
                continue;
            }
            Layout.Size size = Doc.Mind.size(n);
            double w = size.width() * scale;
            double h = size.height() * scale;
            double x = cx + p.x() * scale - w / 2;
            double y = cy + p.y() * scale - h / 2;
            boolean focused = n.id().equals(focus);
            sketch.tag("node").fill(x, y, w, h, h / 2, focused ? amber : box);
            if (fresh.contains(n.id()) && !focused) {
                sketch.tag("fresh").outline(x, y, w, h, h / 2, Math.max(1, 1.5 * scale), amber);
            }
            double text = LABEL * scale;
            sketch.tag("label").text(n.shownLabel(), x + Doc.Mind.PAD / 2 * scale, y + h / 2 + text * 0.35, text,
                    focused ? onAmber : fresh.contains(n.id()) ? ink : dim);
        }
        stage.picture(sketch.picture());
    }
}
