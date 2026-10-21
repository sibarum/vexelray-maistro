package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Parsing the CLI's stream-json output.
 *
 * <p>Fixtures in {@code src/test/resources/agent/}: {@code real-*} were captured from Claude Code 2.1.299 on this
 * machine (paths scrubbed). The CLI could not authenticate from a child process here, so those turns end in its
 * "Not logged in" error. {@code synth-*} are written by hand in the same format, from the shapes of real session
 * transcripts, to cover streamed text, thinking, tool calls and tool results.
 */
class EventParserTest {

    static List<String> fixture(String name) {
        try (InputStream in = EventParserTest.class.getResourceAsStream("/agent/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records events by kind, in order, as a list of short strings, and keeps the typed events. */
    static final class Recorder implements AgentListener {
        final List<String> kinds = new ArrayList<>();
        final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
            kinds.add(event.getClass().getSimpleName());
        }

        <T extends AgentEvent> List<T> all(Class<T> type) {
            List<T> out = new ArrayList<>();
            for (AgentEvent e : events) {
                if (type.isInstance(e)) {
                    out.add(type.cast(e));
                }
            }
            return out;
        }
    }

    static Recorder parseAll(List<String> lines) {
        Recorder r = new Recorder();
        for (String line : lines) {
            for (AgentEvent e : EventParser.parse(line)) {
                r.onEvent(e);
            }
        }
        return r;
    }

    @Test
    void realNotLoggedInTurn() {
        Recorder r = parseAll(fixture("real-not-logged-in.jsonl"));
        assertEquals(List.of("SessionInit", "Status", "ApiError", "Result"), r.kinds);

        AgentEvent.SessionInit init = r.all(AgentEvent.SessionInit.class).getFirst();
        assertEquals("e4a99990-e9a8-4d05-8b99-e155bb9a74ee", init.sessionId());
        assertEquals("claude-haiku-5-5", init.model());
        assertEquals("C:\\work\\demo", init.cwd());
        assertEquals("default", init.permissionMode());
        assertEquals("2.1.299", init.cliVersion());
        assertTrue(init.tools().contains("Read"));
        assertTrue(init.tools().contains("Bash"));
        assertTrue(init.raw().get("mcp_servers").isArray());

        assertEquals("requesting", r.all(AgentEvent.Status.class).getFirst().status());

        AgentEvent.ApiError err = r.all(AgentEvent.ApiError.class).getFirst();
        assertEquals("authentication_failed", err.error());
        assertTrue(err.text().startsWith("Not logged in"));

        // The surprise: subtype "success", but is_error true. success follows is_error.
        AgentEvent.Result res = r.all(AgentEvent.Result.class).getFirst();
        assertFalse(res.success());
        assertEquals("success", res.subtype());
        assertEquals("api_error", res.terminalReason());
        assertEquals(1, res.numTurns());
        assertEquals(0.0, res.costUsd());
        assertTrue(res.resultText().startsWith("Not logged in"));
    }

    @Test
    void realInterruptAcknowledgement() {
        Recorder r = parseAll(fixture("real-interrupt-then-turn.jsonl"));
        assertEquals("ControlResponse", r.kinds.getFirst());
        AgentEvent.ControlResponse c = r.all(AgentEvent.ControlResponse.class).getFirst();
        assertEquals("req_1", c.requestId());
        assertTrue(c.success());
        assertTrue(c.response().get("still_queued").isArray());
    }

    @Test
    void streamedTextAndThinkingTurn() {
        Recorder r = parseAll(fixture("synth-text-turn.jsonl"));
        assertEquals(List.of("SessionInit", "Status", "StreamMarker", "StreamMarker", "ThinkingDelta", "StreamMarker",
                "StreamMarker", "Thinking", "StreamMarker", "TextDelta", "TextDelta", "StreamMarker", "AssistantText",
                "StreamMarker", "StreamMarker", "Result"), r.kinds);

        List<AgentEvent.StreamMarker> markers = r.all(AgentEvent.StreamMarker.class);
        assertEquals("message_start", markers.get(0).eventType());
        assertEquals("msg_01TextTurn", markers.get(0).messageId());
        assertEquals("thinking", markers.get(1).blockType());
        assertEquals("content_block_delta/signature_delta", markers.get(2).eventType());
        assertEquals("text", markers.get(4).blockType());
        assertEquals(1, markers.get(4).index());

        assertEquals("A short greeting.", r.all(AgentEvent.ThinkingDelta.class).getFirst().text());
        StringBuilder streamed = new StringBuilder();
        for (AgentEvent.TextDelta d : r.all(AgentEvent.TextDelta.class)) {
            assertEquals(1, d.index());
            streamed.append(d.text());
        }
        String full = "Hello there, friend! \uD83D\uDC4B";
        assertEquals(full, streamed.toString());

        AgentEvent.AssistantText text = r.all(AgentEvent.AssistantText.class).getFirst();
        assertEquals(full, text.text());
        assertEquals("msg_01TextTurn", text.messageId());
        assertEquals("", text.parentToolUseId());
        AgentEvent.Thinking th = r.all(AgentEvent.Thinking.class).getFirst();
        assertEquals("A short greeting.", th.text());
        assertFalse(th.redacted());

        AgentEvent.Result res = r.all(AgentEvent.Result.class).getFirst();
        assertTrue(res.success());
        assertEquals(full, res.resultText());
        assertEquals(1834, res.durationMs());
        assertEquals(1702, res.durationApiMs());
        assertEquals(0.000412, res.costUsd(), 1e-12);
        assertEquals(new AgentEvent.Usage(3, 14, 1200, 15000), res.usage());
        assertEquals("", res.terminalReason());
    }

    @Test
    void toolUseAndToolResultTurn() {
        Recorder r = parseAll(fixture("synth-tool-turn.jsonl"));
        assertEquals("plan", r.all(AgentEvent.SessionInit.class).getFirst().permissionMode());

        AgentEvent.StreamMarker start = r.all(AgentEvent.StreamMarker.class).get(1);
        assertEquals("tool_use", start.blockType());
        assertEquals("Glob", start.event().get("content_block").get("name").asString());

        StringBuilder partial = new StringBuilder();
        for (AgentEvent.ToolInputDelta d : r.all(AgentEvent.ToolInputDelta.class)) {
            partial.append(d.partialJson());
        }
        assertEquals("*.txt", Json.parse(partial.toString()).get("pattern").asString());

        List<AgentEvent.ToolUse> uses = r.all(AgentEvent.ToolUse.class);
        assertEquals(2, uses.size());
        assertEquals("toolu_01GlobCall", uses.get(0).id());
        assertEquals("Glob", uses.get(0).name());
        assertEquals("{\"pattern\":\"*.txt\"}", uses.get(0).input().toJson());
        assertEquals("Read", uses.get(1).name());
        assertEquals("C:/work/demo/missing.txt", uses.get(1).input().get("file_path").asString());

        List<AgentEvent.ToolResult> results = r.all(AgentEvent.ToolResult.class);
        assertEquals(2, results.size());
        assertEquals("toolu_01GlobCall", results.get(0).toolUseId());
        assertEquals("C:/work/demo/a.txt\nC:/work/demo/b.txt", results.get(0).text());
        assertFalse(results.get(0).isError());
        assertEquals("toolu_01ReadCall", results.get(1).toolUseId());
        assertTrue(results.get(1).isError());
        assertEquals("<tool_use_error>File does not exist.</tool_use_error>", results.get(1).text());
        assertTrue(results.get(1).content().isArray());

        AgentEvent.Result res = r.all(AgentEvent.Result.class).getFirst();
        assertFalse(res.success());
        assertEquals("error_max_turns", res.subtype());
        assertEquals("max_turns", res.terminalReason());
        assertEquals(2, res.numTurns());
        assertTrue(r.all(AgentEvent.Unknown.class).isEmpty());
    }

    @Test
    void badLinesBecomeUnknownWithTheRawLine() {
        for (String line : List.of("not json", "[1,2]", "{\"type\":\"brand_new\"}", "{\"no_type\":1}",
                "{\"type\":\"system\",\"subtype\":\"compact_boundary\"}", "{\"type\":\"stream_event\",\"event\":{}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"server_tool_use\"}]}}",
                "{\"type\":\"result\"")) {
            List<AgentEvent> events = EventParser.parse(line);
            assertEquals(1, events.size(), line);
            AgentEvent.Unknown u = (AgentEvent.Unknown) events.getFirst();
            assertEquals(line, u.line());
            assertFalse(u.reason().isEmpty());
        }
        assertTrue(EventParser.parse("").isEmpty());
        assertTrue(EventParser.parse("   ").isEmpty());
        assertTrue(EventParser.parse(null).isEmpty());
    }

    /** The shape the Agent SDK documents for {@code can_use_tool}; not yet checked against a recorded real one. */
    @Test
    void aPermissionPromptIsItsOwnEvent() {
        String line = "{\"type\":\"control_request\",\"request_id\":\"r-7\",\"request\":{\"subtype\":\"can_use_tool\","
                + "\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf build\"},\"tool_use_id\":\"toolu_1\","
                + "\"permission_suggestions\":[]}}";
        AgentEvent.PermissionRequest p = (AgentEvent.PermissionRequest) EventParser.parse(line).getFirst();
        assertEquals("r-7", p.requestId());
        assertEquals("Bash", p.toolName());
        assertEquals("rm -rf build", p.input().get("command").asString());
        assertEquals("toolu_1", p.toolUseId());

        AgentEvent.ControlRequest other = (AgentEvent.ControlRequest) EventParser.parse(
                "{\"type\":\"control_request\",\"request_id\":\"r-8\",\"request\":{\"subtype\":\"hook_callback\"}}")
                .getFirst();
        assertEquals("hook_callback", other.subtype());
    }

    @Test
    void multiBlockAssistantLineYieldsOneEventPerBlock() {
        String line = "{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_parent\",\"message\":{\"id\":\"m1\","
                + "\"content\":[{\"type\":\"redacted_thinking\",\"data\":\"x\"},{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";
        Recorder r = parseAll(List.of(line));
        assertEquals(List.of("Thinking", "AssistantText", "ToolUse"), r.kinds);
        assertTrue(r.all(AgentEvent.Thinking.class).getFirst().redacted());
        assertEquals("toolu_parent", r.all(AgentEvent.ToolUse.class).getFirst().parentToolUseId());
    }

    @Test
    void aPermissionPromptMissingFieldsIsStillAsked() {
        Recorder r = parseAll(List.of("{\"type\":\"control_request\",\"request_id\":\"r9\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}"));
        AgentEvent.PermissionRequest p = r.all(AgentEvent.PermissionRequest.class).getFirst();
        assertEquals("r9", p.requestId());
        assertEquals("Bash", p.toolName());
        assertEquals("", p.toolUseId());
        assertFalse(p.input().isObject());
    }

    @Test
    void sinkRoutesByKind() {
        List<String> seen = new ArrayList<>();
        AgentEvent.Sink sink = new AgentEvent.Sink() {
            @Override
            public void onTextDelta(AgentEvent.TextDelta e) {
                seen.add("delta:" + e.text());
            }

            @Override
            public void onResult(AgentEvent.Result e) {
                seen.add("result:" + e.success());
            }
        };
        for (String line : fixture("synth-text-turn.jsonl")) {
            for (AgentEvent e : EventParser.parse(line)) {
                e.to(sink);
            }
        }
        assertEquals(List.of("delta:Hello there, ", "delta:friend! \uD83D\uDC4B", "result:true"), seen);
    }
}
