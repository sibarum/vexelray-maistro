package dev.vexelray.maistro.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The concept graph as a value: operations return new graphs, merges are idempotent, carves stick. */
class MapGraphTest {

    private static Set<String> ids(MapGraph g) {
        return g.nodes().stream().map(Node::id).collect(Collectors.toSet());
    }

    private static MapGraph chain() {
        // a - b - c - d, plus e off b
        return MapGraph.EMPTY
                .withNode(Node.of("a", "A", 1))
                .withNode(Node.of("b", "B", 1))
                .withNode(Node.of("c", "C", 2))
                .withNode(Node.of("d", "D", 3))
                .withNode(Node.of("e", "E", 3))
                .withEdge(new Edge("a", "b", "", 1))
                .withEdge(new Edge("b", "c", "", 2))
                .withEdge(new Edge("c", "d", "", 3))
                .withEdge(new Edge("e", "b", "", 3));
    }

    @Test
    void operationsLeaveTheOriginalAlone() {
        MapGraph g = MapGraph.EMPTY.withNode(Node.of("a", "A", 1));
        MapGraph h = g.withNode(Node.of("b", "B", 2)).withEdge(new Edge("a", "b", "", 2)).focus("a");
        assertEquals(1, g.nodeCount());
        assertEquals(0, g.edgeCount());
        assertTrue(g.focus().isEmpty());
        assertEquals(2, h.nodeCount());
        assertEquals(1, h.edgeCount());
        assertEquals("a", h.focus().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> h.nodes().clear());
        assertEquals(0, MapGraph.EMPTY.nodeCount());
    }

    @Test
    void mergingANodeIsIdempotentAndKeepsItsTurn() {
        MapGraph g = MapGraph.EMPTY.withNode(Node.of("a", "A", 1));
        MapGraph again = g.withNode(Node.of("a", "A", 1));
        assertEquals(g, again);
        MapGraph merged = g.withNode(new Node("a", "Alpha", Kind.FILE, 5, 0.7));
        Node a = merged.node("a").orElseThrow();
        assertEquals(1, merged.nodeCount());
        assertEquals(1, a.turn());
        assertEquals("Alpha", a.label());
        assertEquals(Kind.FILE, a.kind());
        assertEquals(0.7, a.surprise());
        // An empty label in a merge keeps the old one.
        assertEquals("Alpha", merged.withNode(new Node("a", "", Kind.FILE, 6, 0)).node("a").orElseThrow().label());
    }

    @Test
    void mergingAnEdgeIsIdempotentAndKeepsItsTurn() {
        MapGraph g = MapGraph.EMPTY.withNode(Node.of("a", "", 1)).withNode(Node.of("b", "", 1))
                .withEdge(new Edge("a", "b", "uses", 1));
        assertEquals(g, g.withEdge(new Edge("a", "b", "", 4)));
        Edge relabelled = g.withEdge(new Edge("a", "b", "owns", 4)).edge("a", "b").orElseThrow();
        assertEquals("owns", relabelled.label());
        assertEquals(1, relabelled.turn());
        assertEquals(2, g.withEdge(new Edge("b", "a", "", 4)).edgeCount(), "direction matters");
    }

    @Test
    void edgesNeverDangle() {
        MapGraph g = MapGraph.EMPTY.withNode(Node.of("a", "", 1));
        assertEquals(0, g.withEdge(new Edge("a", "missing", "", 1)).edgeCount());
    }

    @Test
    void aCarveRemovesTheNodeAndItsEdgesAndBlocksReAdding() {
        MapGraph g = chain().focus("b").carve("b", "wrong reading", 4);
        assertFalse(g.contains("b"));
        assertEquals(1, g.edgeCount(), "a-b, b-c and e-b go; c-d stays");
        assertTrue(g.focus().isEmpty(), "carving the focus clears it");
        assertEquals("wrong reading", g.carve("b").orElseThrow().reason());

        MapGraph readded = g.withNode(Node.of("b", "B", 5)).withEdge(new Edge("a", "b", "", 5));
        assertEquals(g, readded);

        MapGraph.Applied applied = g.applyReport(new MapDelta(
                List.of(new MapDelta.NodeSpec("b", "B", Kind.CONCEPT, 0)),
                List.of(new MapDelta.EdgeSpec("a", "b", "")), List.of(), null, List.of()), 5);
        assertFalse(applied.graph().contains("b"));
        assertEquals(List.of(), applied.addedNodes());
        assertEquals(2, applied.refused().size());

        MapGraph uncarved = g.uncarve("b").withNode(Node.of("b", "B", 6));
        assertTrue(uncarved.contains("b"));
        assertEquals(6, uncarved.node("b").orElseThrow().turn());
        assertFalse(uncarved.isCarved("b"));
    }

    @Test
    void lastTurnIsTheNodesAddedInTheHighestTurn() {
        MapGraph g = chain();
        assertEquals(3, g.latestTurn());
        assertEquals(List.of("d", "e"), g.addedInLatestTurn().stream().map(Node::id).toList());
        // A merge in a later turn does not make an old node "new".
        MapGraph merged = g.withNode(Node.of("a", "A again", 9)).withNode(Node.of("f", "F", 4));
        assertEquals(List.of("f"), merged.addedInLatestTurn().stream().map(Node::id).toList());
        assertEquals(List.of(), MapGraph.EMPTY.addedInLatestTurn());
    }

    @Test
    void neighbourhoodFollowsEdgesBothWaysToDepth() {
        MapGraph g = chain();
        assertEquals(Set.of("b"), ids(g.neighbourhood("b", 0)));
        assertEquals(Set.of("a", "b", "c", "e"), ids(g.neighbourhood("b", 1)));
        assertEquals(Set.of("a", "b", "c", "d", "e"), ids(g.neighbourhood("b", 2)));
        MapGraph one = g.neighbourhood("d", 1);
        assertEquals(Set.of("c", "d"), ids(one));
        assertEquals(1, one.edgeCount());
        assertEquals("d", one.focus().orElseThrow());
        assertTrue(g.neighbourhood("zz", 3).isEmpty());
        assertEquals(List.of("a", "c", "e"), g.neighbours("b"));
    }

    @Test
    void focusNeedsANode() {
        MapGraph g = chain();
        assertSame(g, g.focus("nope"));
        assertEquals("c", g.focus("c").focus().orElseThrow());
        assertTrue(g.focus("c").clearFocus().focus().isEmpty());
    }

    @Test
    void applyStampsTheTurnAndCreatesUndeclaredEnds() {
        MapDelta d = new MapDelta(
                List.of(new MapDelta.NodeSpec("x", "X", Kind.APP, 0.4)),
                List.of(new MapDelta.EdgeSpec("x", "y", "drives")),
                List.of(), "y", List.of());
        MapGraph.Applied r = MapGraph.EMPTY.applyReport(d, 7);
        MapGraph g = r.graph();
        assertEquals(List.of("x", "y"), r.addedNodes());
        assertEquals(1, r.addedEdges());
        assertEquals(7, g.node("y").orElseThrow().turn());
        assertEquals("y", g.node("y").orElseThrow().shownLabel());
        assertEquals("y", g.focus().orElseThrow());
        assertEquals(Kind.APP, g.node("x").orElseThrow().kind());
    }

    @Test
    void recordsRejectBadIdsAndClampSurprise() {
        assertThrows(IllegalArgumentException.class, () -> Node.of("has space", "", 1));
        assertThrows(IllegalArgumentException.class, () -> Node.of("", "", 1));
        assertEquals(1.0, new Node("a", "", Kind.CONCEPT, 1, 3).surprise());
        assertEquals(0.0, new Node("a", "", Kind.CONCEPT, 1, Double.NaN).surprise());
    }
}
