package dev.vexelray.maistro;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a driver
 * waits for the application to be ready, and it needs no hook when readiness is declared somewhere the read-model
 * can already see. {@link #STATUS_AGENT} works that way: it says what the agent is doing, so
 * {@code await status.agent idle} waits for a reply to finish.
 */
final class Landmarks {

    /** The icon rail, and its three pane toggles. */
    static final String RAIL = "rail";
    static final String RAIL_CONVERSATION = "rail.conversation";
    static final String RAIL_MAP = "rail.map";
    static final String RAIL_CONTEXT = "rail.context";

    /** The conversation pane, its transcript, and the composer under it. */
    static final String CONVERSATION = "conversation";
    static final String TRANSCRIPT = "transcript";
    static final String COMPOSER = "composer";
    static final String SEND = "send";

    /** The mind map, the main stage. */
    static final String MAP = "map";

    /** The context stream, and its session readouts. */
    static final String CONTEXT = "context";
    static final String CONTEXT_SESSION = "context.session";

    /** The status line, and its slots. */
    static final String STATUS = "status";
    static final String STATUS_AGENT = "status.agent";
    static final String STATUS_MESSAGE = "status.message";
    static final String STATUS_MODEL = "status.model";

    private Landmarks() {
    }
}
