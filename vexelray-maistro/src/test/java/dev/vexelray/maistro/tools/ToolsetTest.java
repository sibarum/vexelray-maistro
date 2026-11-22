package dev.vexelray.maistro.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsetTest {

    @TempDir
    Path dir;

    private static McpServer server(String name, Tool... tools) {
        return new McpServer(name, "test", List.of(tools));
    }

    private static Tool said(String name, boolean readOnly) {
        return McpServerTest.tool(name, readOnly, JsonValue.Null.NULL, a -> Tool.Result.text(name));
    }

    @Test
    void oneConfigNamesEveryServer() throws Exception {
        Toolset t = new Toolset(List.of(server("maistro", said("apps", true)), server("maistro-fs",
                said("read", true))), dir.resolve("mcp.json"));
        assertEquals("{\"mcpServers\":{\"maistro\":{\"type\":\"sdk\",\"name\":\"maistro\"},"
                + "\"maistro-fs\":{\"type\":\"sdk\",\"name\":\"maistro-fs\"}}}",
                Files.readString(t.config(), StandardCharsets.UTF_8));
        t.close();
    }

    @Test
    void everyReadOnlyToolIsAllowedAndNothingElse() {
        Toolset t = new Toolset(List.of(server("maistro", said("apps", true)),
                server("maistro-fs", said("read", true), said("write", false))), dir.resolve("mcp.json"));
        assertEquals(List.of("mcp__maistro__apps", "mcp__maistro-fs__read"), t.allowed());
        t.close();
    }

    @Test
    void twoServersOfOneNameAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Toolset(List.of(server("maistro"),
                server("maistro")), dir.resolve("mcp.json")));
    }

    @Test
    void eachMessageGoesToTheServerItNames() throws Exception {
        Tool app = McpServerTest.tool("which", true, JsonValue.Null.NULL, a -> Tool.Result.text("app"));
        Tool fs = McpServerTest.tool("which", true, JsonValue.Null.NULL, a -> Tool.Result.text("fs"));
        Toolset t = new Toolset(List.of(server("maistro", app), server("maistro-fs", fs)), dir.resolve("mcp.json"));
        assertTrue(t.serves("maistro-fs"));
        assertFalse(t.serves("maistro-git"));
        JsonValue call = Json.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"which\",\"arguments\":{}}}");
        CompletableFuture<JsonValue> answer = new CompletableFuture<>();
        t.handle("maistro-fs", call, answer::complete);
        assertEquals("fs", answer.get().get("result").get("content").get(0).get("text").asString());
        assertThrows(IllegalArgumentException.class, () -> t.handle("maistro-git", call, r -> { }));
        t.close();
    }
}
