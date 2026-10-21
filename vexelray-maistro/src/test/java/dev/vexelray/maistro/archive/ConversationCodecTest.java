package dev.vexelray.maistro.archive;

import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.Edge;
import dev.vexelray.maistro.map.Kind;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;
import dev.vexelray.maistro.map.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCodecTest {

    private static Conversation sample() {
        Transcript t = Transcript.empty()
                .append(id -> Turn.human(id, "Why \"surprise\"?\nSecond line").withChips(List.of("from · KV cache")))
                .append(id -> Turn.agent(id, "Because **it** drives eviction.", false).withChips(List.of("map · +2")))
                .append(id -> Turn.tool(id, "Bash", "rm -rf build").finished(true).withAsk(Turn.Ask.DENIED))
                .append(id -> Turn.note(id, "Interrupted", true));
        MapGraph g = MapGraph.EMPTY
                .withNode(new Node("kv", "KV cache", Kind.CONCEPT, 1, 0.25))
                .withNode(new Node("src/Main.java", "Main", Kind.FILE, 2, 0))
                .withNode(Node.of("gone", "Gone", 2))
                .withEdge(new Edge("kv", "src/Main.java", "lives in", 2))
                .carve("gone", "wrong turn", 3)
                .focus("kv");
        return new Conversation("20261018-120000-ab12", "Why \"surprise\"?", 1000, 2000,
                new Conversation.Agent("sess-1", "claude-opus-5-5", "C:/work", 0.0123, 3, 40, 50), t, g,
                Map.of("kv", new Layout.Point(1.5, -2), "src/Main.java", new Layout.Point(100, 40)), 3);
    }

    @Test
    void aConversationComesBackAsItWent() {
        Conversation c = sample();
        Conversation back = ConversationCodec.decode(ConversationCodec.encode(c));
        assertEquals(c.id(), back.id());
        assertEquals(c.title(), back.title());
        assertEquals(c.started(), back.started());
        assertEquals(c.saved(), back.saved());
        assertEquals(c.agent(), back.agent());
        assertEquals(c.transcript(), back.transcript());
        assertEquals(c.graph(), back.graph());
        assertEquals(c.positions(), back.positions());
        assertEquals(c.mindTurn(), back.mindTurn());
        assertTrue(back.graph().isCarved("gone"));
        assertEquals("kv", back.graph().focus().orElseThrow());
    }

    @Test
    void nothingComesBackLiveOrWaiting() {
        Transcript t = Transcript.empty()
                .append(id -> Turn.agent(id, "half a rep", true))
                .append(id -> Turn.tool(id, "Bash", "ls").withAsk(Turn.Ask.PENDING));
        Conversation c = new Conversation("x", "", 0, 0, null, t, null, null, 0);
        Transcript back = ConversationCodec.decode(ConversationCodec.encode(c)).transcript();
        assertTrue(back.turns().stream().noneMatch(Turn::live));
        assertEquals(Turn.Ask.LAPSED, back.turns().get(1).ask());
        assertTrue(back.asking().isEmpty());
    }

    @Test
    void aFileFromAnotherBuildLoadsWhatHoldsTogether() {
        Conversation c = ConversationCodec.decode("""
                {"id":"old","transcript":{"nextId":1,"turns":[{"id":7,"who":"SOMEONE","text":"hi"}]},
                 "map":{"nodes":[{"id":"a"},{"id":"bad id"}],"edges":[{"from":"a","to":"missing"}],
                        "positions":{"a":[1,2],"b":[3]}}}""");
        assertEquals(Turn.Who.NOTE, c.transcript().turns().getFirst().who(), "an unknown speaker is a note");
        assertEquals(8, c.transcript().nextId(), "the next id is past every saved one");
        assertEquals(1, c.graph().nodeCount());
        assertEquals(0, c.graph().edgeCount());
        assertEquals(Map.of("a", new Layout.Point(1, 2)), c.positions());
        assertEquals("", c.agent().sessionId());
    }

    @Test
    void aFileThatIsNotAConversationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ConversationCodec.decode("{\"title\":\"no id\"}"));
        assertThrows(IllegalArgumentException.class, () -> ConversationCodec.decode("not json"));
    }

    @Test
    void aTitleIsTheFirstLineCutShort() {
        assertEquals("Fix the build", Conversation.titleFrom("\n  Fix the build  \nand more"));
        String longLine = "x".repeat(100);
        String title = Conversation.titleFrom(longLine);
        assertEquals(Conversation.TITLE_MAX, title.length());
        assertTrue(title.endsWith("…"));
        assertFalse(Conversation.titleFrom("   ").contains(" "));
    }
}
