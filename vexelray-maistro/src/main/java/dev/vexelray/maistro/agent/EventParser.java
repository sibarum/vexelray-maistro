package dev.vexelray.maistro.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one line of {@code claude -p --output-format stream-json --verbose [--include-partial-messages]} output into
 * events. Stateless and safe to share.
 *
 * <p>Never throws on input: a line that is not JSON, or is JSON of an unknown shape, becomes an
 * {@link AgentEvent.Unknown} carrying the raw line. One line can yield several events (an assistant line whose
 * message holds more than one content block); a blank line yields none.
 *
 * <p>The line types, as Claude Code 2.1.x writes them:
 * <ul>
 *   <li>{@code system} / {@code init} and {@code status}: {@link AgentEvent.SessionInit}, {@link AgentEvent.Status}</li>
 *   <li>{@code assistant}: one event per content block, {@link AgentEvent.AssistantText}, {@link AgentEvent.Thinking},
 *       {@link AgentEvent.ToolUse}; or {@link AgentEvent.ApiError} for the CLI's own synthetic error message</li>
 *   <li>{@code user}: {@link AgentEvent.ToolResult} per {@code tool_result} block, else {@link AgentEvent.UserMessage}</li>
 *   <li>{@code stream_event}: {@link AgentEvent.TextDelta}, {@link AgentEvent.ThinkingDelta},
 *       {@link AgentEvent.ToolInputDelta}, else {@link AgentEvent.StreamMarker}</li>
 *   <li>{@code result}: {@link AgentEvent.Result}</li>
 *   <li>{@code control_response}: {@link AgentEvent.ControlResponse}</li>
 *   <li>{@code control_request}: {@link AgentEvent.PermissionRequest} for {@code can_use_tool}, else
 *       {@link AgentEvent.ControlRequest}</li>
 * </ul>
 */
public final class EventParser {

    private EventParser() {
    }

    /** The events in one output line. */
    public static List<AgentEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        JsonValue v;
        try {
            v = Json.parse(line);
        } catch (JsonException e) {
            return List.of(new AgentEvent.Unknown(line, "not JSON: " + e.getMessage()));
        }
        if (!v.isObject()) {
            return List.of(new AgentEvent.Unknown(line, "not a JSON object"));
        }
        String type = v.get("type").asString();
        List<AgentEvent> out = new ArrayList<>(1);
        switch (type) {
            case "system" -> system(line, v, out);
            case "assistant" -> assistant(line, v, out);
            case "user" -> user(line, v, out);
            case "stream_event" -> stream(line, v, out);
            case "result" -> out.add(result(v));
            case "control_response" -> out.add(controlResponse(v));
            case "control_request" -> out.add(controlRequest(v));
            default -> out.add(new AgentEvent.Unknown(line, "unknown line type '" + type + "'"));
        }
        return out;
    }

    private static void system(String line, JsonValue v, List<AgentEvent> out) {
        String subtype = v.get("subtype").asString();
        String sessionId = v.get("session_id").asString();
        if (subtype.equals("init")) {
            List<String> tools = new ArrayList<>();
            for (JsonValue t : v.get("tools").items()) {
                if (t.isString()) {
                    tools.add(t.asString());
                }
            }
            out.add(new AgentEvent.SessionInit(sessionId, v.get("model").asString(), v.get("cwd").asString(),
                    v.get("permissionMode").asString(), v.get("claude_code_version").asString(), tools, v));
        } else if (subtype.equals("status")) {
            out.add(new AgentEvent.Status(v.get("status").asString(), sessionId));
        } else {
            out.add(new AgentEvent.Unknown(line, "unknown system subtype '" + subtype + "'"));
        }
    }

    private static void assistant(String line, JsonValue v, List<AgentEvent> out) {
        JsonValue message = v.get("message");
        String parent = v.get("parent_tool_use_id").asString();
        String messageId = message.get("id").asString();
        JsonValue content = message.get("content");

        if (v.get("is_api_error_message").asBool(false) || v.get("error").isString()) {
            out.add(new AgentEvent.ApiError(v.get("error").asString(), joinText(content)));
            return;
        }
        if (content.isString()) {
            out.add(new AgentEvent.AssistantText(messageId, content.asString(), parent));
            return;
        }
        for (JsonValue block : content.items()) {
            String bt = block.get("type").asString();
            switch (bt) {
                case "text" -> out.add(new AgentEvent.AssistantText(messageId, block.get("text").asString(), parent));
                case "thinking" -> out.add(new AgentEvent.Thinking(messageId, block.get("thinking").asString(), false,
                        parent));
                case "redacted_thinking" -> out.add(new AgentEvent.Thinking(messageId, "", true, parent));
                case "tool_use" -> out.add(new AgentEvent.ToolUse(messageId, block.get("id").asString(),
                        block.get("name").asString(), block.get("input"), parent));
                default -> out.add(new AgentEvent.Unknown(line, "unknown assistant block type '" + bt + "'"));
            }
        }
        if (out.isEmpty()) {
            out.add(new AgentEvent.Unknown(line, "assistant message with no content"));
        }
    }

    private static void user(String line, JsonValue v, List<AgentEvent> out) {
        JsonValue content = v.get("message").get("content");
        String parent = v.get("parent_tool_use_id").asString();
        if (content.isString()) {
            out.add(new AgentEvent.UserMessage(content.asString(), content));
            return;
        }
        boolean other = false;
        for (JsonValue block : content.items()) {
            if (block.get("type").asString().equals("tool_result")) {
                JsonValue c = block.get("content");
                out.add(new AgentEvent.ToolResult(block.get("tool_use_id").asString(), joinText(c), c,
                        block.get("is_error").asBool(false), parent));
            } else {
                other = true;
            }
        }
        if (other) {
            out.add(new AgentEvent.UserMessage(joinText(content), content));
        }
        if (out.isEmpty()) {
            out.add(new AgentEvent.Unknown(line, "user message with no content"));
        }
    }

    private static void stream(String line, JsonValue v, List<AgentEvent> out) {
        JsonValue e = v.get("event");
        String parent = v.get("parent_tool_use_id").asString();
        String et = e.get("type").asString();
        int index = (int) e.get("index").asLong(0);
        if (et.equals("content_block_delta")) {
            JsonValue d = e.get("delta");
            String dt = d.get("type").asString();
            switch (dt) {
                case "text_delta" -> out.add(new AgentEvent.TextDelta(index, d.get("text").asString(), parent));
                case "thinking_delta" -> out.add(new AgentEvent.ThinkingDelta(index, d.get("thinking").asString(),
                        parent));
                case "input_json_delta" -> out.add(new AgentEvent.ToolInputDelta(index,
                        d.get("partial_json").asString(), parent));
                // signature_delta and anything newer: a marker, so it is seen but not mistaken for text
                default -> out.add(new AgentEvent.StreamMarker(et + "/" + dt, index, "", "", parent, e));
            }
            return;
        }
        if (et.isEmpty()) {
            out.add(new AgentEvent.Unknown(line, "stream_event without an event type"));
            return;
        }
        String blockType = et.equals("content_block_start") ? e.get("content_block").get("type").asString() : "";
        String messageId = et.equals("message_start") ? e.get("message").get("id").asString() : "";
        out.add(new AgentEvent.StreamMarker(et, index, blockType, messageId, parent, e));
    }

    private static AgentEvent result(JsonValue v) {
        boolean isError = v.has("is_error")
                ? v.get("is_error").asBool(true)
                : !v.get("subtype").asString().equals("success");
        JsonValue u = v.get("usage");
        AgentEvent.Usage usage = u.isObject()
                ? new AgentEvent.Usage(u.get("input_tokens").asLong(0), u.get("output_tokens").asLong(0),
                        u.get("cache_creation_input_tokens").asLong(0), u.get("cache_read_input_tokens").asLong(0))
                : AgentEvent.Usage.NONE;
        String text = v.get("result").asString();
        if (text.isEmpty() && v.get("errors").isArray()) {
            text = joinText(v.get("errors"));
        }
        return new AgentEvent.Result(!isError, v.get("subtype").asString(), text, v.get("session_id").asString(),
                v.get("total_cost_usd").asDouble(0), v.get("duration_ms").asLong(0), v.get("duration_api_ms").asLong(0),
                (int) v.get("num_turns").asLong(0), usage, v.get("terminal_reason").asString(), v);
    }

    /** A request from the CLI: a permission prompt as its own record, anything else as it came. */
    private static AgentEvent controlRequest(JsonValue v) {
        String id = v.get("request_id").asString();
        JsonValue r = v.get("request");
        String subtype = r.get("subtype").asString();
        if (subtype.equals("can_use_tool")) {
            return new AgentEvent.PermissionRequest(id, r.get("tool_name").asString(), r.get("input"),
                    r.get("tool_use_id").asString(), r);
        }
        return new AgentEvent.ControlRequest(id, subtype, r);
    }

    private static AgentEvent controlResponse(JsonValue v) {
        JsonValue r = v.get("response");
        return new AgentEvent.ControlResponse(r.get("request_id").asString(), r.get("subtype").asString()
                .equals("success"), r.get("error").asString(), r.get("response"));
    }

    /** A string as is; the text parts of a block list (or a list of strings) joined by newlines; else "". */
    static String joinText(JsonValue content) {
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonValue part : content.items()) {
            String t = part.isString() ? part.asString()
                    : part.get("type").asString().equals("text") ? part.get("text").asString() : null;
            if (t != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
