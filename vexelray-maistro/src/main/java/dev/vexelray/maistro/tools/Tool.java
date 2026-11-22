package dev.vexelray.maistro.tools;

import dev.vexelray.maistro.agent.JsonValue;

import java.util.Base64;
import java.util.List;

/**
 * One tool Maistro gives the agent: its name, what it is for, the shape of its input as a JSON schema, and what it
 * does. {@link McpServer} lists and calls them; the agent sees each as {@code mcp__<server>__<name>}.
 *
 * <p>A call runs on a tool thread, never on the frame loop or the CLI's reader, so it may block on a socket or the
 * disk. It answers a {@link Result} rather than throwing: a failure is something the agent should read and act on.
 */
public interface Tool {

    /** The name the agent calls it by, under the server's prefix: lowercase, no spaces. */
    String name();

    /** What it does and when to use it, for the model. */
    String description();

    /** The JSON schema of the input, an {@code object}. */
    JsonValue inputSchema();

    /**
     * The JSON schema of what a successful call returns as {@link Result#structured}, an {@code object}; or
     * {@link JsonValue.Null#NULL} for a tool whose results are content only. A tool that declares one must give a
     * structured result on every success (MCP 2025-06-18, <i>Structured content</i>): {@link McpServer} turns a
     * success without one into an error rather than send the CLI a result its schema does not allow.
     */
    default JsonValue outputSchema() {
        return JsonValue.Null.NULL;
    }

    /**
     * True when the tool changes nothing outside Maistro: it reads, lists or looks. Said to the CLI as MCP's
     * {@code readOnlyHint}, and a read-only tool runs without asking the human ({@link Toolset#allowed}); every other
     * tool goes through the permission prompt.
     */
    default boolean readOnly() {
        return false;
    }

    /** Runs the tool on {@code arguments} (an object, possibly empty). */
    Result call(JsonValue arguments);

    /**
     * What a call gives back: MCP content blocks, what the call found as JSON, and whether the call failed.
     *
     * @param content    the blocks, in order: {@code text}, {@code image}, ... A structured result carries one short
     *                   text line here, for reading
     * @param structured the result as an object matching the tool's {@link #outputSchema}, sent as
     *                   {@code structuredContent}; {@link JsonValue.Null#NULL} when there is none
     * @param isError    true when the tool could not do what was asked; the content says why
     */
    record Result(List<JsonValue> content, JsonValue structured, boolean isError) {

        public Result {
            content = List.copyOf(content);
            if (!structured.isNull() && !structured.isObject()) {
                throw new IllegalArgumentException("structuredContent is an object, not " + structured.toJson());
            }
        }

        /** Content blocks only. */
        public Result(List<JsonValue> content, boolean isError) {
            this(content, JsonValue.Null.NULL, isError);
        }

        /** A text answer. */
        public static Result text(String text) {
            return new Result(List.of(textBlock(text)), false);
        }

        /** What a call found, as {@code data}, with {@code summary} the line a reader sees. */
        public static Result structured(String summary, JsonValue.Obj data) {
            return new Result(List.of(textBlock(summary)), data, false);
        }

        /** A failure, said in words. */
        public static Result error(String text) {
            return new Result(List.of(textBlock(text)), true);
        }

        /** A text line and a PNG. */
        public static Result image(String text, byte[] png) {
            return new Result(List.of(textBlock(text), JsonValue.Obj.builder()
                    .put("type", "image")
                    .put("data", Base64.getEncoder().encodeToString(png))
                    .put("mimeType", "image/png")
                    .build()), false);
        }

        /** As {@code tools/call} answers it. */
        public JsonValue toJson() {
            JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("content", new JsonValue.Arr(content));
            if (structured.isObject()) {
                b.put("structuredContent", structured);
            }
            return b.put("isError", isError).build();
        }

        private static JsonValue textBlock(String text) {
            return JsonValue.Obj.builder().put("type", "text").put("text", text).build();
        }
    }
}
