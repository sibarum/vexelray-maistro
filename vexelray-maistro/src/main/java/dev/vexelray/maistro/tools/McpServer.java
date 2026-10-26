package dev.vexelray.maistro.tools;

import dev.vexelray.maistro.agent.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An MCP server that lives in this process: the JSON-RPC side of the tools the CLI reaches through
 * {@code control_request} / {@code mcp_message} (a {@code "type": "sdk"} server in its {@code --mcp-config}).
 *
 * <p>{@link #answer} takes one message and gives the reply to send back. It handles what the CLI was recorded sending
 * ({@code src/test/resources/agent/real-mcp-handshake.jsonl}): {@code initialize}, {@code notifications/initialized}
 * and {@code tools/list}, and {@code tools/call}, which follows the MCP specification and has not been seen from this
 * CLI yet, because seeing one needs a logged-in run. Every message is answered, a notification with an empty result
 * as the CLI accepted in that recording, since the CLI waits on each.
 *
 * <p>Answering may run a tool, so it may block: call it on a tool thread ({@link Toolset}).
 */
public final class McpServer {

    /** The MCP revision this server speaks. */
    static final String PROTOCOL = "2025-06-18";

    /** JSON-RPC's code for a method nobody serves. */
    static final int METHOD_NOT_FOUND = -32601;

    private final String name;
    private final String version;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * @param name    the server's name; the agent sees its tools as {@code mcp__<name>__<tool>}
     * @param version said in {@code initialize}
     * @param tools   what it serves, listed in this order
     */
    public McpServer(String name, String version, List<Tool> tools) {
        this.name = name;
        this.version = version;
        for (Tool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    public String name() {
        return name;
    }

    /** The CLI's {@code --mcp-config} for this server: in this process, reached over the control channel. */
    public JsonValue config() {
        return JsonValue.Obj.builder()
                .put("mcpServers", JsonValue.Obj.builder()
                        .put(name, JsonValue.Obj.builder().put("type", "sdk").put("name", name).build())
                        .build())
                .build();
    }

    /** The full name the agent knows {@code tool} by. */
    public String qualified(String tool) {
        return "mcp__" + name + "__" + tool;
    }

    /** Every tool's full name, in order. */
    public List<String> qualifiedNames() {
        List<String> out = new ArrayList<>();
        for (String t : tools.keySet()) {
            out.add(qualified(t));
        }
        return out;
    }

    /** The reply to {@code message}. */
    public JsonValue answer(JsonValue message) {
        JsonValue id = message.get("id");
        String method = message.get("method").asString();
        if (id.isNull()) {
            return JsonValue.Obj.builder().put("jsonrpc", "2.0").put("result", JsonValue.Obj.EMPTY).build();
        }
        return switch (method) {
            case "initialize" -> result(id, JsonValue.Obj.builder()
                    .put("protocolVersion", PROTOCOL)
                    .put("capabilities", JsonValue.Obj.builder().put("tools", JsonValue.Obj.EMPTY).build())
                    .put("serverInfo", JsonValue.Obj.builder().put("name", name).put("version", version).build())
                    .build());
            case "ping" -> result(id, JsonValue.Obj.EMPTY);
            case "tools/list" -> result(id, JsonValue.Obj.builder().put("tools", list()).build());
            case "tools/call" -> result(id, call(message.get("params")).toJson());
            default -> error(id, METHOD_NOT_FOUND, "no method " + method);
        };
    }

    private JsonValue list() {
        List<JsonValue> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            out.add(JsonValue.Obj.builder()
                    .put("name", t.name())
                    .put("description", t.description())
                    .put("inputSchema", t.inputSchema())
                    .build());
        }
        return new JsonValue.Arr(out);
    }

    private Tool.Result call(JsonValue params) {
        String tool = params.get("name").asString();
        Tool t = tools.get(tool);
        if (t == null) {
            return Tool.Result.error("Maistro has no tool " + tool + "; it has " + String.join(", ", tools.keySet()));
        }
        JsonValue args = params.get("arguments");
        try {
            return t.call(args.isObject() ? args : JsonValue.Obj.EMPTY);
        } catch (RuntimeException e) {
            return Tool.Result.error(tool + " failed: " + e);
        }
    }

    private static JsonValue result(JsonValue id, JsonValue result) {
        return JsonValue.Obj.builder().put("jsonrpc", "2.0").put("id", id).put("result", result).build();
    }

    private static JsonValue error(JsonValue id, int code, String message) {
        return JsonValue.Obj.builder()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JsonValue.Obj.builder().put("code", code).put("message", message).build())
                .build();
    }
}
