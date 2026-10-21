package dev.vexelray.maistro.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout as a function: deterministic, stable when the graph grows, and free of overlaps. */
class LayoutTest {

    /** Labels vary, so boxes do: width from the label, a fixed height. */
    private static final Function<Node, Layout.Size> SIZES =
            n -> new Layout.Size(30 + 7 * n.shownLabel().length(), 24);

    /** A deterministic, tree-ish graph with a few cross links, grown one node per turn. */
    private static MapGraph graph(int count) {
        MapGraph g = MapGraph.EMPTY;
        for (int i = 0; i < count; i++) {
            g = g.withNode(Node.of("n" + i, "concept " + "x".repeat(i % 7), i));
            if (i > 0) {
                g = g.withEdge(new Edge("n" + ((i * 37 + 11) % i), "n" + i, "", i));
            }
            if (i > 4 && i % 5 == 0) {
                g = g.withEdge(new Edge("n" + i, "n" + (i - 3), "", i));
            }
        }
        return g;
    }

    /** Grows the layout as the conversation would: one call per turn. */
    private static Map<String, Layout.Point> grow(MapGraph full) {
        Map<String, Layout.Point> pos = Map.of();
        List<Node> nodes = new ArrayList<>(full.nodes());
        for (int t = 0; t < nodes.size(); t++) {
            final int turn = t;
            MapGraph g = MapGraph.EMPTY;
            for (Node n : nodes) {
                if (n.turn() <= turn) {
                    g = g.withNode(n);
                }
            }
            for (Edge e : full.edges()) {
                g = g.withEdge(e);
            }
            pos = Layout.next(pos, g, SIZES);
        }
        return pos;
    }

    private static void assertNoOverlaps(Map<String, Layout.Point> pos, MapGraph g) {
        List<Node> nodes = new ArrayList<>(g.nodes());
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node a = nodes.get(i);
                Node b = nodes.get(j);
                Layout.Point pa = pos.get(a.id());
                Layout.Point pb = pos.get(b.id());
                Layout.Size sa = SIZES.apply(a);
                Layout.Size sb = SIZES.apply(b);
                boolean overlapX = Math.abs(pa.x() - pb.x()) < (sa.width() + sb.width()) / 2;
                boolean overlapY = Math.abs(pa.y() - pb.y()) < (sa.height() + sb.height()) / 2;
                assertTrue(!(overlapX && overlapY), a.id() + " overlaps " + b.id());
            }
        }
    }

    @Test
    void sameInputsGiveTheSamePositions() {
        MapGraph g = graph(30);
        assertEquals(Layout.next(Map.of(), g, SIZES), Layout.next(Map.of(), g, SIZES));
        assertEquals(grow(g), grow(g));
    }

    @Test
    void aFreshThirtyNodeLayoutHasNoOverlaps() {
        MapGraph g = graph(30);
        Map<String, Layout.Point> pos = Layout.next(Map.of(), g, SIZES);
        assertEquals(30, pos.size());
        assertNoOverlaps(pos, g);
    }

    @Test
    void aGrownThirtyNodeLayoutHasNoOverlaps() {
        MapGraph g = graph(30);
        assertNoOverlaps(grow(g), g);
    }

    @Test
    void oldNodesBarelyMoveWhenOneIsAdded() {
        MapGraph g = graph(20);
        Map<String, Layout.Point> before = Layout.next(Map.of(), g, SIZES);
        MapGraph grown = g.withNode(Node.of("new", "a new idea", 21)).withEdge(new Edge("n7", "new", "", 21));
        Map<String, Layout.Point> after = Layout.next(before, grown, SIZES);
        double worst = 0;
        for (Map.Entry<String, Layout.Point> e : before.entrySet()) {
            Layout.Point a = after.get(e.getKey());
            worst = Math.max(worst, Math.hypot(a.x() - e.getValue().x(), a.y() - e.getValue().y()));
        }
        assertTrue(worst < 5, "an old node moved " + worst);
        Layout.Point anchor = after.get("n7");
        Layout.Point fresh = after.get("new");
        assertTrue(Math.hypot(anchor.x() - fresh.x(), anchor.y() - fresh.y()) < Layout.EDGE_LENGTH * 3,
                "the new node lands near its neighbour");
        assertNoOverlaps(after, grown);
    }

    @Test
    void nothingNewMeansNothingMoves() {
        MapGraph g = graph(15);
        Map<String, Layout.Point> pos = Layout.next(Map.of(), g, SIZES);
        assertEquals(pos, Layout.next(pos, g, SIZES));
    }

    @Test
    void removedNodesAreDroppedAndFitBoundsEveryBox() {
        MapGraph g = graph(10);
        Map<String, Layout.Point> pos = Layout.next(Map.of(), g, SIZES);
        MapGraph smaller = g.carve("n3", "gone", 11);
        Map<String, Layout.Point> next = Layout.next(pos, smaller, SIZES);
        assertEquals(9, next.size());
        assertTrue(!next.containsKey("n3"));

        Layout.Box box = Layout.fit(next, smaller, SIZES);
        for (Node n : smaller.nodes()) {
            Layout.Point p = next.get(n.id());
            Layout.Size s = SIZES.apply(n);
            assertTrue(p.x() - s.width() / 2 >= box.minX() - 1e-9 && p.x() + s.width() / 2 <= box.maxX() + 1e-9);
            assertTrue(p.y() - s.height() / 2 >= box.minY() - 1e-9 && p.y() + s.height() / 2 <= box.maxY() + 1e-9);
        }
        double scale = box.scaleToFit(800, 600, 20);
        assertTrue(box.width() * scale <= 760 + 1e-6 && box.height() * scale <= 560 + 1e-6);
        assertEquals(new Layout.Box(0, 0, 0, 0), Layout.fit(Map.of(), MapGraph.EMPTY, SIZES));
        assertEquals(Map.of(), Layout.next(Map.of(), MapGraph.EMPTY, SIZES));
    }

    @Test
    void theFirstNodeSitsAtTheOrigin() {
        MapGraph g = MapGraph.EMPTY.withNode(Node.of("only", "Only", 1));
        assertEquals(new Layout.Point(0, 0), Layout.next(Map.of(), g, SIZES).get("only"));
    }
}
