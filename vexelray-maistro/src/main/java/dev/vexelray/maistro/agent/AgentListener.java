package dev.vexelray.maistro.agent;

/**
 * Receives an {@link AgentSession}'s events.
 *
 * <p><b>Threading.</b> Every method is called on the session's stdout reader thread, one event at a time and in the
 * order the CLI wrote them; {@link AgentEvent.ProcessExit} is always last. A listener must not block for long (the
 * CLI stalls once its stdout pipe fills) and must hand anything for the window to the application's own lanes. It
 * may call {@link AgentSession#send}, {@link AgentSession#interrupt} and {@link AgentSession#close} from here. An
 * exception thrown by a listener is logged and the stream goes on.
 *
 * <p>Either override {@link #onEvent} to see every event, or the {@code on...} methods of {@link AgentEvent.Sink}
 * for the kinds you want; the rest do nothing.
 */
public interface AgentListener extends AgentEvent.Sink {

    /** Every event arrives here first; the default routes it to the {@code on...} method for its kind. */
    default void onEvent(AgentEvent event) {
        event.to(this);
    }
}
