package dev.vexelray.maistro.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeTest {

    /** a — b on the main map, and c, which the branch never saw. */
    private static final MapGraph MAIN = MapGraph.EMPTY
            .withNode(Node.of("a", "Alpha", 1))
            .withNode(Node.of("b", "Beta", 1))
            .withNode(Node.of("c", "Gamma", 2))
            .withEdge(new Edge("a", "b", "", 1));

    /** What a branch from a was given. */
    private static final MapGraph GIVEN = MAIN.neighbourhood("a", 1);

    @Test
    void whatTheBranchWasGivenIsLeftAsItWas() {
        Merge m = Merge.of(MAIN, GIVEN, "b1", 5);
        assertTrue(m.isEmpty());
        assertEquals(MAIN, m.graph());
    }

    @Test
    void newNodesAndLinksComeInAsNew() {
        MapGraph branch = GIVEN.withNode(Node.of("x", "Xi", 1)).withEdge(new Edge("a", "x", "leads to", 1))
                .withEdge(new Edge("b", "a", "back", 1));
        Merge m = Merge.of(MAIN, branch, "b1", 5);
        assertEquals(1, m.nodes());
        assertEquals(2, m.links(), "a link between two given nodes is new too");
        assertEquals(5, m.graph().node("x").orElseThrow().turn(), "stamped with the main turn, so drawn as new");
        assertEquals("leads to", m.graph().edge("a", "x").orElseThrow().label());
        assertEquals(List.of("x", "a", "b"), m.added().nodes().stream().map(Node::id).toList(),
                "what was added, with the ends of its links");
        assertTrue(m.renamed().isEmpty());
    }

    @Test
    void anIdUsedForSomethingElseIsRenamedAndItsLinksFollow() {
        MapGraph branch = GIVEN
                .withNode(Node.of("b", "Bee", 1))
                .withNode(Node.of("c", "Not gamma", 1))
                .withEdge(new Edge("a", "c", "", 1));
        Merge m = Merge.of(MAIN, branch, "b1", 5);
        assertEquals(Map.of("b", "b~b1", "c", "c~b1"), m.renamed());
        assertEquals("Beta", m.graph().node("b").orElseThrow().label(), "the main map's node is untouched");
        assertEquals("Gamma", m.graph().node("c").orElseThrow().label());
        assertEquals("Not gamma", m.graph().node("c~b1").orElseThrow().label());
        assertTrue(m.graph().edge("a", "c~b1").isPresent());
        assertFalse(m.graph().edge("a", "c").isPresent());
    }

    @Test
    void aRenameNeverLandsOnAnIdInUse() {
        MapGraph main = MAIN.withNode(Node.of("c~b1", "Taken", 3));
        Merge m = Merge.of(main, GIVEN.withNode(Node.of("c", "Other", 1)), "b1", 5);
        assertEquals("c~b1-2", m.renamed().get("c"));
    }

    @Test
    void whatTheHumanCarvedStaysOut() {
        MapGraph main = MAIN.carve("x", "wrong", 2);
        Merge m = Merge.of(main, GIVEN.withNode(Node.of("x", "Xi", 1)).withEdge(new Edge("a", "x", "", 1)), "b1", 5);
        assertTrue(m.isEmpty());
        assertFalse(m.graph().contains("x"));
    }

    @Test
    void aBranchsOwnCarvesAndFocusAreNotMerged() {
        MapGraph branch = GIVEN.carve("b", "not here", 1).focus("a");
        Merge m = Merge.of(MAIN.focus("b"), branch, "b1", 5);
        assertTrue(m.graph().contains("b"));
        assertFalse(m.graph().isCarved("b"));
        assertEquals("b", m.graph().focus().orElseThrow());
    }
}
