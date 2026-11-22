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
    /** Over the composer: {@code from · <label>} while the next message starts from a node, else empty. */
    static final String COMPOSER_FROM = "composer.from";
    /** The ✕ that lets that node go. */
    static final String COMPOSER_FROM_CLEAR = "composer.from.clear";

    /**
     * Allow and Deny under the oldest tool turn waiting on the human; unbound while none waits. A script that does
     * not care which turn asked: {@code await status.agent waiting}, then {@code click ask.allow}.
     */
    static final String ASK_ALLOW = "ask.allow";
    static final String ASK_DENY = "ask.deny";

    /** The first standing-permission offer under the oldest waiting tool turn; unbound when it has none. */
    static final String ASK_ALWAYS = "ask.always";

    /** Offer {@code n} (from 0) under the tool turn {@code turnId}. */
    static String askAlways(long turnId, int n) {
        return "ask." + turnId + ".always." + n;
    }

    /** Allow under the tool turn {@code turnId}, whether or not it is the oldest waiting. */
    static String askAllow(long turnId) {
        return "ask." + turnId + ".allow";
    }

    /** Deny under the tool turn {@code turnId}. */
    static String askDeny(long turnId) {
        return "ask." + turnId + ".deny";
    }

    /** A strand's button over the transcript, by its id: {@code strand.main}, {@code strand.b1}. */
    static String strand(String id) {
        return "strand." + id;
    }

    /** Abandon, beside the strand buttons while a branch is on screen. */
    static final String BRANCH_ABANDON = "branch.abandon";

    /** Merge into main, beside Abandon. */
    static final String BRANCH_MERGE = "branch.merge";

    /** "Branch from here", in the context stream's Focus section while a node is focused. */
    static final String CONTEXT_BRANCH = "context.branch";

    /** The list of saved conversations (Ctrl+O), and one row of it by the conversation's id. */
    static final String CONVERSATIONS = "conversations";

    static String conversationRow(String id) {
        return CONVERSATIONS + "." + id;
    }

    /** The mind map, the main stage. */
    static final String MAP = "map";

    /**
     * One node of the mind map, by its id: {@code click map.node.kv-cache} focuses it. A landmark may not hold a
     * {@code '/'}, which a node id may, so one becomes a {@code ':'} here.
     */
    static String mapNode(String id) {
        return MAP + ".node." + id.replace('/', ':');
    }

    /** The context stream, and its session readouts. */
    static final String CONTEXT = "context";
    static final String CONTEXT_SESSION = "context.session";
    /** The focused node's label, empty when nothing is focused: {@code await context.focus KV cache}. */
    static final String CONTEXT_FOCUS = "context.focus";

    /**
     * The card over the transcript while Claude Code is not signed in, signing in, or not found; unshown otherwise. Its
     * title says which, and {@code signin.detail} what the CLI said when a sign-in failed. Signed out,
     * {@code signin.browser}, {@code signin.sso} and {@code signin.console} sign in in the browser;
     * {@code signin.command} is the command that does it by hand, and the two buttons below it copy it and check
     * again. Signing in, {@code signin.link} is the sign-in page's address once the CLI has said it, beside Copy link
     * and Cancel.
     */
    static final String SIGN_IN = "signin";
    static final String SIGN_IN_TITLE = "signin.title";
    static final String SIGN_IN_DETAIL = "signin.detail";
    static final String SIGN_IN_BROWSER = "signin.browser";
    static final String SIGN_IN_SSO = "signin.sso";
    static final String SIGN_IN_CONSOLE = "signin.console";
    static final String SIGN_IN_COMMAND = "signin.command";
    static final String SIGN_IN_COPY = "signin.copy";
    static final String SIGN_IN_CHECK = "signin.check";
    static final String SIGN_IN_LINK = "signin.link";
    static final String SIGN_IN_COPY_LINK = "signin.copylink";
    static final String SIGN_IN_CANCEL = "signin.cancel";

    /** The status line, and its slots. */
    static final String STATUS = "status";
    static final String STATUS_AGENT = "status.agent";
    static final String STATUS_MESSAGE = "status.message";
    static final String STATUS_MODEL = "status.model";
    /** Whether Claude Code is signed in: {@code await status.account signed in}. */
    static final String STATUS_ACCOUNT = "status.account";

    private Landmarks() {
    }
}
