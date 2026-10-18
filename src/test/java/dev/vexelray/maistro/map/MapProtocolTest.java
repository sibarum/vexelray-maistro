package dev.vexelray.maistro.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map block in a reply: found, parsed tolerantly, and taken out of the prose. */
class MapProtocolTest {

    @Test
    void parsesABlockAndStripsItFromTheProse() {
        String reply = """
                The cache evicts by surprise.

                ```maistro-map
                node kv-cache "KV cache" concept
                node surprise "Surprise score" concept 0.8
                edge surprise kv-cache "drives eviction"
                focus kv-cache
                ```
                """;
        MapProtocol.Parsed p = MapProtocol.parse(reply);
        assertEquals("The cache evicts by surprise.", p.prose());
        MapDelta d = p.delta();
        assertEquals(List.of(), d.problems());
        assertEquals(2, d.nodes().size());
        assertEquals(new MapDelta.NodeSpec("surprise", "Surprise score", Kind.CONCEPT, 0.8), d.nodes().get(1));
        assertEquals(new MapDelta.EdgeSpec("surprise", "kv-cache", "drives eviction"), d.edges().getFirst());
        assertEquals("kv-cache", d.focus());

        MapGraph g = MapGraph.EMPTY.apply(d, 1);
        assertEquals(2, g.nodeCount());
        assertEquals(1, g.edgeCount());
        assertEquals("kv-cache", g.focus().orElseThrow());
    }

    @Test
    void readsEveryBlockAndKeepsProseBetweenThem() {
        String reply = """
                First part.
                ```maistro-map
                node a "A"
                ```
                Middle part.

                ~~~~ maistro-map
                node b "B" file
                edge a -> b
                carve old "turned out irrelevant"
                ~~~~
                Last part.
                ```java
                int x = 1;
                ```""";
        MapProtocol.Parsed p = MapProtocol.parse(reply);
        assertEquals("First part.\nMiddle part.\n\nLast part.\n```java\nint x = 1;\n```", p.prose());
        assertEquals(List.of("a", "b"), p.delta().nodes().stream().map(MapDelta.NodeSpec::id).toList());
        assertEquals(Kind.FILE, p.delta().nodes().get(1).kind());
        assertEquals(new MapDelta.EdgeSpec("a", "b", ""), p.delta().edges().getFirst());
        assertEquals(new MapDelta.CarveSpec("old", "turned out irrelevant"), p.delta().carves().getFirst());
        assertTrue(p.delta().problems().isEmpty());
    }

    @Test
    void skipsMalformedLinesAndReportsThem() {
        String reply = """
                Prose.
                ```maistro-map
                # a comment
                node
                node ok "Fine" gizmo 1.5
                edge lonely
                wibble a b
                node "quoted-id" "x"
                node bad "unterminated
                focus a b
                edge x y "lbl" extra
                node good "Good \\"quoted\\" label" question
                ```""";
        MapProtocol.Parsed p = MapProtocol.parse(reply);
        assertEquals("Prose.", p.prose());
        MapDelta d = p.delta();
        assertEquals(List.of("ok", "good"), d.nodes().stream().map(MapDelta.NodeSpec::id).toList());
        MapDelta.NodeSpec ok = d.nodes().getFirst();
        assertEquals(Kind.CONCEPT, ok.kind());
        assertEquals(1.0, new Node(ok.id(), ok.label(), ok.kind(), 1, ok.surprise()).surprise());
        assertEquals("Good \"quoted\" label", d.nodes().get(1).label());
        assertEquals(Kind.QUESTION, d.nodes().get(1).kind());
        assertEquals(List.of(new MapDelta.EdgeSpec("x", "y", "lbl")), d.edges());
        assertEquals(null, d.focus());
        // node, ok (partly), edge lonely, wibble, quoted id, unterminated, focus a b, extra word
        assertEquals(8, d.problems().size());
        assertEquals(4, d.problems().getFirst().line(), "line numbers count from the top of the reply");
        assertTrue(d.problems().get(1).reason().contains("gizmo"));
    }

    @Test
    void anUnclosedBlockRunsToTheEndAndIsReported() {
        MapProtocol.Parsed p = MapProtocol.parse("Text.\n```maistro-map\nnode a \"A\"\n");
        assertEquals("Text.", p.prose());
        assertEquals(1, p.delta().nodes().size());
        assertEquals(1, p.delta().problems().size());
        assertEquals(2, p.delta().problems().getFirst().line());
    }

    @Test
    void aReplyWithNoBlockIsAllProse() {
        MapProtocol.Parsed p = MapProtocol.parse("Just words.\r\n\r\n\r\nMore words.  \r\n");
        assertEquals("Just words.\n\nMore words.", p.prose());
        assertTrue(p.delta().isEmpty());
        assertTrue(MapProtocol.parse(null).delta().isEmpty());
    }

    @Test
    void renderRoundTrips() {
        MapGraph g = MapGraph.EMPTY
                .withNode(new Node("a", "Say \"hi\"", Kind.APP, 1, 0.25))
                .withNode(Node.of("b", "B", 1))
                .withNode(Node.of("gone", "", 1))
                .withEdge(new Edge("a", "b", "links", 1))
                .carve("gone", "no", 1)
                .focus("b");
        MapGraph back = MapGraph.EMPTY.apply(MapProtocol.parse(MapProtocol.render(g)).delta(), 1);
        assertEquals(g, back);
    }

    @Test
    void instructionsNameTheTagAndEveryStatement() {
        String s = MapProtocol.INSTRUCTIONS;
        assertTrue(s.contains(MapProtocol.TAG));
        for (String word : List.of("node ", "edge ", "focus ", "carve ")) {
            assertTrue(s.contains(word), word);
        }
        assertFalse(s.contains("\n"), "one paragraph");
    }
}
