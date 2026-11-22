package dev.vexelray.maistro.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vexelray.maistro.agent.AgentEvent;
import dev.vexelray.maistro.agent.EventParser;
import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class McpServerTest {

    static final Tool ECHO = new Tool() {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes text back.";
        }

        @Override
        public JsonValue inputSchema() {
            return Json.parse("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
        }

        @Override
        public Result call(JsonValue arguments) {
            return Result.text("echo: " + arguments.get("text").asString());
        }
    };

    static final Tool BROKEN = new Tool() {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public String description() {
            return "Throws.";
        }

        @Override
        public JsonValue inputSchema() {
            return JsonValue.Obj.EMPTY;
        }

        @Override
        public Result call(JsonValue arguments) {
            throw new IllegalStateException("bug");
        }
    };

    final McpServer server = new McpServer("maistro", "test", List.of(ECHO, BROKEN));

    /** The messages the CLI sent in the recorded handshake, in order. */
    static List<JsonValue> recorded() throws IOException {
        return recorded("real-mcp-handshake.jsonl");
    }

    /** The MCP messages the CLI sent in recording {@code name}, in order. */
    static List<JsonValue> recorded(String name) throws IOException {
        try (InputStream in = McpServerTest.class.getResourceAsStream("/agent/" + name)) {
            List<JsonValue> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                for (AgentEvent e : EventParser.parse(line)) {
                    if (e instanceof AgentEvent.McpMessage m) {
                        out.add(m.message());
                    }
                }
            }
            return out;
        }
    }

    @Test
    void answersTheRecordedHandshake() throws IOException {
        List<JsonValue> messages = recorded();
        assertEquals(3, messages.size());

        JsonValue init = server.answer(messages.get(0));
        assertEquals(0, init.get("id").asLong(-1));
        assertEquals(McpServer.PROTOCOL, init.get("result").get("protocolVersion").asString());
        assertTrue(init.get("result").get("capabilities").get("tools").isObject());
        assertEquals("maistro", init.get("result").get("serverInfo").get("name").asString());

        // A notification: answered all the same, since the CLI waits on every message; this is the reply it took.
        assertEquals("{\"jsonrpc\":\"2.0\",\"result\":{}}", server.answer(messages.get(1)).toJson());

        JsonValue list = server.answer(messages.get(2));
        assertEquals(1, list.get("id").asLong(-1));
        JsonValue tools = list.get("result").get("tools");
        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).get("name").asString());
        assertEquals("Echoes text back.", tools.get(0).get("description").asString());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asString());
    }

    @Test
    void callsATool() {
        JsonValue r = server.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}"));
        assertEquals(7, r.get("id").asLong(-1));
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"echo: hi\"}],\"isError\":false}",
                r.get("result").toJson());
    }

    @Test
    void aToolsFailureIsAnErrorResultNotAProtocolError() {
        JsonValue unknown = server.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\"}}"));
        assertTrue(unknown.get("result").get("isError").asBool(false));
        assertTrue(unknown.get("result").get("content").get(0).get("text").asString().contains("echo, broken"));

        JsonValue thrown = server.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"broken\"}}"));
        assertTrue(thrown.get("result").get("isError").asBool(false));
        assertTrue(thrown.get("result").get("content").get(0).get("text").asString().contains("bug"));
    }

    @Test
    void anUnknownMethodIsAJsonRpcError() {
        JsonValue r = server.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"resources/list\"}"));
        assertEquals("x", r.get("id").asString());
        assertEquals(McpServer.METHOD_NOT_FOUND, r.get("error").get("code").asLong(0));
        assertFalse(r.has("result"));
    }

    @Test
    void theConfigEntryNamesAnInProcessServer() {
        assertEquals("{\"type\":\"sdk\",\"name\":\"maistro\"}", server.configEntry().toJson());
        assertEquals(List.of("mcp__maistro__echo", "mcp__maistro__broken"), server.qualifiedNames());
    }

    /**
     * {@code real-mcp-tool-call.jsonl}: a whole turn from claude.exe 2.1.330, signed in, asked to call
     * {@code mcp__maistro__apps} once. Its {@code tools/call} carries a {@code _meta} the server ignores.
     */
    @Test
    void answersTheRecordedToolCall() throws IOException {
        List<JsonValue> messages = recorded("real-mcp-tool-call.jsonl");
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                messages.stream().map(m -> m.get("method").asString()).toList());
        JsonValue call = messages.get(3);
        assertEquals("apps", call.get("params").get("name").asString());
        assertTrue(call.get("params").get("_meta").get("claudecode/toolUseId").asString().startsWith("toolu_"));

        McpServer apps = new McpServer("maistro", "test", List.of(tool("apps", true, JsonValue.Null.NULL,
                a -> Tool.Result.text("no apps"))));
        JsonValue r = apps.answer(call);
        assertEquals(2, r.get("id").asLong(-1));
        assertEquals("no apps", r.get("result").get("content").get(0).get("text").asString());
        assertFalse(r.get("result").get("isError").asBool(true));
    }

    /**
     * {@code real-mcp-structured-call.jsonl}: claude.exe 2.1.330 given two in-process servers in one config, asked to
     * use {@code mcp__maistro-fs__read}. It shakes hands with each by name and calls the tool on the server it belongs
     * to; and it hands the agent a structured result as the JSON of its {@code structuredContent}, not the summary
     * line beside it.
     */
    @Test
    void theCliTalksToEachServerByName() throws IOException {
        List<String> said = new ArrayList<>();
        String toolResult = "";
        try (InputStream in = McpServerTest.class.getResourceAsStream("/agent/real-mcp-structured-call.jsonl")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                for (AgentEvent e : EventParser.parse(line)) {
                    if (e instanceof AgentEvent.McpMessage m) {
                        said.add(m.serverName() + " " + m.message().get("method").asString());
                    }
                }
                if (line.contains("\"type\":\"tool_result\"") && line.contains("\\\"sha256\\\"")) {
                    toolResult = line;
                }
            }
        }
        assertTrue(said.containsAll(List.of("maistro initialize", "maistro-fs initialize", "maistro tools/list",
                "maistro-fs tools/list", "maistro-fs tools/call")), said.toString());
        JsonValue content = Json.parse(toolResult).get("message").get("content").get(0).get("content");
        assertTrue(content.isString(), "the structured result, as text");
        assertEquals("utf-8", Json.parse(content.asString()).get("encoding").asString());
    }

    static final JsonValue COUNT_SCHEMA = Json.parse(
            "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[\"n\"]}");

    static Tool tool(String name, boolean readOnly, JsonValue outputSchema,
                     java.util.function.Function<JsonValue, Tool.Result> run) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public JsonValue inputSchema() {
                return Json.parse("{\"type\":\"object\",\"properties\":{}}");
            }

            @Override
            public JsonValue outputSchema() {
                return outputSchema;
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public Tool.Result call(JsonValue arguments) {
                return run.apply(arguments);
            }
        };
    }

    private static JsonValue call(McpServer s, String tool) {
        return s.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":{}}}")).get("result");
    }

    @Test
    void aStructuredResultCarriesItsLineAndItsObject() {
        McpServer s = new McpServer("t", "test", List.of(tool("count", true, COUNT_SCHEMA,
                a -> Tool.Result.structured("3 things", JsonValue.Obj.builder().put("n", 3).build()))));
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"3 things\"}],\"structuredContent\":{\"n\":3},"
                + "\"isError\":false}", call(s, "count").toJson());
        JsonValue listed = s.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .get("result").get("tools").get(0);
        assertEquals(COUNT_SCHEMA.toJson(), listed.get("outputSchema").toJson());
        assertTrue(listed.get("annotations").get("readOnlyHint").asBool(false));
    }

    @Test
    void aToolWithNoOutputSchemaListsNone() {
        JsonValue listed = server.answer(Json.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .get("result").get("tools").get(0);
        assertFalse(listed.has("outputSchema"));
        assertFalse(listed.get("annotations").get("readOnlyHint").asBool(true), "a tool is not read-only unless "
                + "it says so");
    }

    @Test
    void aSuccessMissingItsDeclaredStructureIsAnError() {
        McpServer s = new McpServer("t", "test", List.of(
                tool("bare", true, COUNT_SCHEMA, a -> Tool.Result.text("forgot")),
                tool("fails", true, COUNT_SCHEMA, a -> Tool.Result.error("could not"))));
        assertTrue(call(s, "bare").get("isError").asBool(false));
        assertTrue(call(s, "bare").get("content").get(0).get("text").asString().contains("no structured result"));
        JsonValue failed = call(s, "fails");
        assertTrue(failed.get("isError").asBool(false), "a failure needs no structure");
        assertEquals("could not", failed.get("content").get(0).get("text").asString());
    }

    @Test
    void structuredContentIsAnObject() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Tool.Result(List.of(), JsonValue.Arr.EMPTY, false));
    }
}
