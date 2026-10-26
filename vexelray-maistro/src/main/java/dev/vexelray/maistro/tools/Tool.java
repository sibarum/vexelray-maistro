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

    /** Runs the tool on {@code arguments} (an object, possibly empty). */
    Result call(JsonValue arguments);

    /**
     * What a call gives back: MCP content blocks, and whether the call failed.
     *
     * @param content the blocks, in order: {@code text}, {@code image}, ...
     * @param isError true when the tool could not do what was asked; the content says why
     */
    record Result(List<JsonValue> content, boolean isError) {

        public Result {
            content = List.copyOf(content);
        }

        /** A text answer. */
        public static Result text(String text) {
            return new Result(List.of(textBlock(text)), false);
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
            return JsonValue.Obj.builder()
                    .put("content", new JsonValue.Arr(content))
                    .put("isError", isError)
                    .build();
        }

        private static JsonValue textBlock(String text) {
            return JsonValue.Obj.builder().put("type", "text").put("text", text).build();
        }
    }
}
