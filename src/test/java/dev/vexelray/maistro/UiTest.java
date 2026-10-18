package dev.vexelray.maistro;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.maistro.agent.AgentEvent;
import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.Edge;
import dev.vexelray.maistro.map.MapGraph;
import dev.vexelray.maistro.map.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maistro's real tree, built headless by the generated wiring (no window, no GPU), with Claude Code's events fed to
 * the {@link Conductor} by hand in the order the CLI sends them. No process is started: nothing here sends.
 */
class UiTest {

    private Shell shell;
    private MaistroAppWiring wiring;

    @BeforeEach
    void build() {
        wiring = new MaistroAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    private static void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void itStartsWithAnEmptyConversationAndEveryPaneUp() {
        Doc doc = wiring.model().doc();
        assertEquals(0, doc.transcript().size());
        assertEquals(Doc.Panes.ALL, doc.panes());
        assertFalse(doc.busy());
    }

    @Test
    void aStreamedReplyBecomesOneAgentTurn() {
        Conductor c = wiring.conductor();
        c.onSessionInit(new AgentEvent.SessionInit("abc-123", "claude-opus-5-5", "C:/work", "default", "2.1",
                List.of("Read"), Json.parse("{}")));
        c.onTextDelta(new AgentEvent.TextDelta(0, "Hel", ""));
        c.onTextDelta(new AgentEvent.TextDelta(0, "lo", ""));
        Doc mid = wiring.model().doc();
        assertEquals("Hello", mid.transcript().last().text());
        assertTrue(mid.transcript().last().live());
        assertTrue(mid.busy());

        c.onAssistantText(new AgentEvent.AssistantText("m1", "Hello.", ""));
        c.onResult(result(true, 0.0123, 1));
        Doc end = wiring.model().doc();
        assertEquals(1, end.transcript().size());
        Turn reply = end.transcript().last();
        assertEquals(Turn.Who.AGENT, reply.who());
        assertEquals("Hello.", reply.text());
        assertFalse(reply.live());
        assertFalse(end.busy());
        assertEquals("idle", end.activity());
        assertEquals("claude-opus-5-5", end.agent().model());
        assertEquals(0.0123, end.agent().costUsd(), 1e-9);
    }

    @Test
    void aToolCallIsATurnOfItsOwnThatEndsWithItsResult() {
        Conductor c = wiring.conductor();
        c.onTextDelta(new AgentEvent.TextDelta(0, "Looking.", ""));
        c.onToolUse(new AgentEvent.ToolUse("m1", "tu1", "Bash", Json.parse("{\"command\":\"ls -la\"}"), ""));
        Turn tool = wiring.model().doc().transcript().last();
        assertEquals("TOOL · Bash", tool.heading());
        assertEquals("ls -la", tool.text());
        assertTrue(tool.live());
        assertEquals("running Bash", wiring.model().doc().activity());
        assertFalse(wiring.model().doc().transcript().turns().getFirst().live(), "the reply before it stopped");

        c.onToolResult(new AgentEvent.ToolResult("tu1", "boom\nmore", Json.parse("null"), true, ""));
        Turn done = wiring.model().doc().transcript().last();
        assertFalse(done.live());
        assertTrue(done.failed());
        assertEquals("ls -la\n→ boom …", done.text());
    }

    @Test
    void aFailedTurnSaysSo() {
        Conductor c = wiring.conductor();
        c.onResult(result(false, 0, 0));
        Turn note = wiring.model().doc().transcript().last();
        assertEquals(Turn.Who.NOTE, note.who());
        assertTrue(note.failed());
    }

    @Test
    void theProcessEndingIsNotedAndLeavesNothingLive() {
        Conductor c = wiring.conductor();
        c.onTextDelta(new AgentEvent.TextDelta(0, "half a rep", ""));
        c.onProcessExit(new AgentEvent.ProcessExit(1));
        Doc doc = wiring.model().doc();
        assertTrue(doc.transcript().turns().stream().noneMatch(Turn::live));
        assertTrue(doc.transcript().last().failed());
        assertFalse(doc.busy());
    }

    @Test
    void aReplysMapBlockGrowsTheMapAndLeavesTheProse() {
        Conductor c = wiring.conductor();
        String reply = """
                The cache evicts by surprise.

                ```maistro-map
                node kv-cache "KV cache"
                node surprise "Surprise score" concept 0.8
                edge surprise kv-cache "drives eviction"
                focus kv-cache
                ```
                """;
        c.onTextDelta(new AgentEvent.TextDelta(0, reply, ""));
        c.onAssistantText(new AgentEvent.AssistantText("m1", reply, ""));
        Doc doc = wiring.model().doc();
        Turn turn = doc.transcript().last();
        assertEquals("The cache evicts by surprise.", turn.text());
        assertEquals(List.of("map · +2 nodes, +1 links", "focus · kv-cache"), turn.chips());
        assertEquals(2, doc.mind().graph().nodeCount());
        assertEquals(2, doc.mind().positions().size(), "every node is placed");
    }

    @Test
    void focusingANodeMovesTheFocusAndNothingElse() {
        Conductor c = wiring.conductor();
        String reply = """
                ```maistro-map
                node kv-cache "KV cache"
                node surprise "Surprise score"
                focus kv-cache
                ```
                """;
        c.onAssistantText(new AgentEvent.AssistantText("m1", reply, ""));
        Doc.Mind before = wiring.model().doc().mind();

        wiring.model().focus("surprise");
        Doc.Mind after = wiring.model().doc().mind();
        assertEquals("surprise", after.graph().focus().orElseThrow());
        assertEquals(before.positions(), after.positions(), "nothing moved");

        wiring.model().focus("no-such-node");
        assertEquals(after, wiring.model().doc().mind());
    }

    @Test
    void theFocusSaysWhatItLinksTo() {
        MapGraph graph = MapGraph.EMPTY
                .withNode(Node.of("a", "KV cache", 1))
                .withNode(Node.of("b", "Surprise score", 1))
                .withNode(Node.of("c", "Eviction", 1))
                .withEdge(new Edge("b", "a", "", 1))
                .withEdge(new Edge("a", "c", "", 1));
        assertEquals("linked to Surprise score, Eviction", ContextPane.linkedTo(graph, "a"));
        assertEquals("linked to KV cache", ContextPane.linkedTo(graph, "c"));
        assertEquals("linked to nothing yet",
                ContextPane.linkedTo(graph.withNode(Node.of("d", "Alone", 1)), "d"));
    }

    @Test
    void aMapNodesLandmarkHasNoSlash() {
        assertEquals("map.node.kv-cache", Landmarks.mapNode("kv-cache"));
        assertEquals("map.node.src:Main.java", Landmarks.mapNode("src/Main.java"));
    }

    @Test
    void aPaneToggleReachesTheModel() throws Exception {
        wiring.model().toggle(Doc.Pane.CONTEXT);
        eventually("the context pane to go", () -> !wiring.model().doc().panes().context());
        assertTrue(wiring.model().doc().panes().conversation());
    }

    @Test
    void aToolCallIsDescribedByWhatItActsOn() {
        assertEquals("src/Main.java", Conductor.describe("Read", Json.parse("{\"file_path\":\"src/Main.java\"}")));
        assertEquals("{\"x\":1}", Conductor.describe("Other", Json.parse("{\"x\":1}")));
    }

    private static AgentEvent.Result result(boolean success, double cost, int turns) {
        return new AgentEvent.Result(success, success ? "success" : "error_during_execution", "", "abc-123", cost,
                10, 8, turns, new AgentEvent.Usage(12, 34, 0, 0), "", Json.parse("{}"));
    }
}
