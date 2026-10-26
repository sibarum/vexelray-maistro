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
        try (InputStream in = McpServerTest.class.getResourceAsStream("/agent/real-mcp-handshake.jsonl")) {
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
    void theConfigNamesAnInProcessServer() {
        assertEquals("{\"mcpServers\":{\"maistro\":{\"type\":\"sdk\",\"name\":\"maistro\"}}}",
                server.config().toJson());
        assertEquals(List.of("mcp__maistro__echo", "mcp__maistro__broken"), server.qualifiedNames());
    }
}
