package dev.vexelray.maistro.agent;

import java.util.List;

/**
 * One thing the CLI said, read from a line of its {@code stream-json} output (see {@link EventParser}), or the end
 * of the process ({@link ProcessExit}).
 *
 * <p>To act on events by kind, implement the matching {@code on...} methods of a {@link Sink} (an
 * {@link AgentListener} is one) and call {@link #to}; each event routes itself to its method, so there is no switch
 * or {@code instanceof} chain over the kinds to keep in step.
 *
 * <p>Strings are never null (an absent field is {@code ""}); numbers default to 0.
 */
public interface AgentEvent {

    /** Calls the method of {@code sink} that handles this kind of event. */
    void to(Sink sink);

    /**
     * Receives events by kind: one method per kind, each a no-op by default, since ignoring most of the stream is
     * normal. Pass one to {@link AgentEvent#to}.
     */
    interface Sink {
        default void onSessionInit(SessionInit e) {
        }

        default void onStatus(Status e) {
        }

        default void onAssistantText(AssistantText e) {
        }

        default void onThinking(Thinking e) {
        }

        default void onToolUse(ToolUse e) {
        }

        default void onToolResult(ToolResult e) {
        }

        default void onUserMessage(UserMessage e) {
        }

        default void onApiError(ApiError e) {
        }

        default void onTextDelta(TextDelta e) {
        }

        default void onThinkingDelta(ThinkingDelta e) {
        }

        default void onToolInputDelta(ToolInputDelta e) {
        }

        default void onStreamMarker(StreamMarker e) {
        }

        default void onResult(Result e) {
        }

        default void onControlResponse(ControlResponse e) {
        }

        default void onControlRequest(ControlRequest e) {
        }

        default void onUnknown(Unknown e) {
        }

        default void onProcessExit(ProcessExit e) {
        }
    }

    /**
     * {@code system/init}: the session started. Sent once per process, after the first user turn arrives (not at
     * launch). {@code raw} is the whole line, for the fields this record does not lift out (mcp_servers, skills,
     * slash_commands, agents, ...).
     */
    record SessionInit(String sessionId, String model, String cwd, String permissionMode, String cliVersion,
                       List<String> tools, JsonValue raw) implements AgentEvent {
        public SessionInit {
            tools = List.copyOf(tools);
        }

        @Override
        public void to(Sink l) {
            l.onSessionInit(this);
        }
    }

    /** {@code system/status}, e.g. {@code "requesting"} while a model request is in flight. */
    record Status(String status, String sessionId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onStatus(this);
        }
    }

    /**
     * A finished text block of an assistant message. The CLI sends each content block of a message as its own
     * {@code assistant} line, all with the same {@code messageId}. {@code parentToolUseId} is non-empty when the
     * message comes from a sub-agent running inside that tool call.
     */
    record AssistantText(String messageId, String text, String parentToolUseId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onAssistantText(this);
        }
    }

    /** A finished thinking block. {@code redacted} blocks carry no readable text. */
    record Thinking(String messageId, String text, boolean redacted, String parentToolUseId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onThinking(this);
        }
    }

    /** The model asked to call a tool. {@code input} is the tool's arguments as JSON (usually an object). */
    record ToolUse(String messageId, String id, String name, JsonValue input, String parentToolUseId)
            implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onToolUse(this);
        }
    }

    /**
     * What a tool call returned, sent by the CLI as a {@code user} line. {@code text} flattens the content (a string,
     * or the text parts of a block list joined by newlines); {@code content} is the content as sent, which may also
     * hold images.
     */
    record ToolResult(String toolUseId, String text, JsonValue content, boolean isError, String parentToolUseId)
            implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onToolResult(this);
        }
    }

    /** A {@code user} line that is not a tool result: a replayed or injected user message. */
    record UserMessage(String text, JsonValue content) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onUserMessage(this);
        }
    }

    /**
     * An assistant message the CLI made up to report a failure instead of a model reply ({@code is_api_error_message},
     * model {@code <synthetic>}): for instance {@code error = "authentication_failed"},
     * {@code text = "Not logged in · Please run /login"}. A {@link Result} with {@code success = false} follows.
     */
    record ApiError(String error, String text) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onApiError(this);
        }
    }

    /** Streamed text of the content block at {@code index} of the message being generated (partial messages only). */
    record TextDelta(int index, String text, String parentToolUseId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onTextDelta(this);
        }
    }

    /** Streamed thinking (partial messages only). */
    record ThinkingDelta(int index, String text, String parentToolUseId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onThinkingDelta(this);
        }
    }

    /** A streamed fragment of a tool call's JSON input; the fragments only parse once joined. */
    record ToolInputDelta(int index, String partialJson, String parentToolUseId) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onToolInputDelta(this);
        }
    }

    /**
     * The other streaming events: {@code message_start}, {@code content_block_start}, {@code content_block_stop},
     * {@code message_delta}, {@code message_stop}. {@code blockType} is the started block's type
     * ({@code text}, {@code thinking}, {@code tool_use}) for {@code content_block_start}, else {@code ""};
     * {@code messageId} is set on {@code message_start}. {@code event} is the raw API event.
     */
    record StreamMarker(String eventType, int index, String blockType, String messageId, String parentToolUseId,
                        JsonValue event) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onStreamMarker(this);
        }
    }

    /** Token counts from a result's {@code usage}. */
    record Usage(long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);
    }

    /**
     * The end of a turn. {@code success} is {@code !is_error}: the CLI's {@code subtype} is not reliable for this
     * (a failed login comes back as {@code subtype "success"} with {@code is_error true}). {@code subtype} is kept
     * as sent ({@code success}, {@code error_max_turns}, {@code error_during_execution}, ...), and
     * {@code terminalReason} likewise ({@code api_error}, ...; may be empty).
     */
    record Result(boolean success, String subtype, String resultText, String sessionId, double costUsd,
                  long durationMs, long durationApiMs, int numTurns, Usage usage, String terminalReason,
                  JsonValue raw) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onResult(this);
        }
    }

    /** The CLI's answer to a {@code control_request} we sent, such as {@link AgentSession#interrupt()}. */
    record ControlResponse(String requestId, boolean success, String error, JsonValue response) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onControlResponse(this);
        }
    }

    /**
     * A {@code control_request} from the CLI to us (for instance {@code can_use_tool}, only sent when it was started
     * with {@code --permission-prompt-tool stdio}). {@link AgentSession} does not answer these yet.
     */
    record ControlRequest(String requestId, String subtype, JsonValue request) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onControlRequest(this);
        }
    }

    /** A line that was not understood: not JSON, or a type this code does not know. Nothing is dropped silently. */
    record Unknown(String line, String reason) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onUnknown(this);
        }
    }

    /**
     * The CLI process ended (its stdout closed and it exited). Always the last event of a session. {@code exitCode}
     * is -1 when it could not be read.
     */
    record ProcessExit(int exitCode) implements AgentEvent {
        @Override
        public void to(Sink l) {
            l.onProcessExit(this);
        }
    }
}
