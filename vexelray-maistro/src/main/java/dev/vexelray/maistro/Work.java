package dev.vexelray.maistro;

import dev.vexelray.maistro.agent.AuthLogin;
import dev.vexelray.maistro.archive.Summary;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.List;

/**
 * Slow work, as messages: each request is heard by a {@code @Job} on the framework's job lane, and each reply by
 * {@link Screen} on the GUI thread. The disk and the CLI's sign-in check go here, never on the GUI thread, whose time
 * is the frame's. Signing in waits on the person in the browser, for as long as they take, so it is a source's
 * ({@link SignInWithBrowser}), on a thread of its own, rather than a job holding one of the job lane's few.
 *
 * <p>Until vexelray-gui 0.2 this was the {@code Gui}'s offload executor, handed a lambda. A job is not a closure: its
 * request and its reply are deeply immutable values, so nothing of a caller's state can be reached from the job lane.
 * A job promises no order between two requests, so where order matters (two saves of one conversation) the caller
 * sends the next when the last one's reply arrives.
 */
final class Work {

    private Work() {
    }

    /** Write conversation {@code id} into {@code dir}, as {@code json}; the {@code number}th snapshot handed over. */
    record Write(Path dir, String id, long number, String json) {
        static final Topic<Write> TOPIC = Topic.edge(Write.class);
    }

    /** {@link Write} done; {@code problem} is empty, or why the file was not written. */
    record Written(String id, long number, String problem) {
        static final Topic<Written> TOPIC = Topic.edge(Written.class);
    }

    /** Read every conversation in {@code dir}, for the list of saved ones. */
    record ListAll(Path dir) {
        static final Topic<ListAll> TOPIC = Topic.edge(ListAll.class);
    }

    /** Every saved conversation, newest first. */
    record Listed(List<Summary> rows) {
        static final Topic<Listed> TOPIC = Topic.edge(Listed.class);

        Listed {
            rows = List.copyOf(rows);
        }
    }

    /** Read conversation {@code id} from {@code dir}, to carry on with it. */
    record Read(Path dir, String id) {
        static final Topic<Read> TOPIC = Topic.edge(Read.class);
    }

    /**
     * Conversation {@code id} as it is kept, its file's text, decoded by whoever hears it (a {@code Conversation} is not
     * a value the framework can prove immutable); or {@code problem}, saying why it could not be read.
     */
    record Loaded(String id, String json, String problem) {
        static final Topic<Loaded> TOPIC = Topic.edge(Loaded.class);
    }

    /** Ask the CLI {@code claude} (empty: find it) whether it is signed in: the {@code check}th time of asking. */
    record AccountCheck(long check, String claude) {
        static final Topic<AccountCheck> TOPIC = Topic.edge(AccountCheck.class);
    }

    /** What the CLI said, for check {@code check}. */
    record AccountChecked(long check, Doc.Account account) {
        static final Topic<AccountChecked> TOPIC = Topic.edge(AccountChecked.class);
    }

    /**
     * Sign the CLI {@code claude} (empty: find it) in, in the browser, with {@code method}: the {@code attempt}th time
     * of asking. Heard by the {@link SignInWithBrowser} source, which runs {@code claude auth login} on a thread of its
     * own for as long as the person takes; a newer request stops the run in progress first.
     */
    record SignIn(long attempt, String claude, AuthLogin.Method method) {
        static final Topic<SignIn> TOPIC = Topic.edge(SignIn.class);
    }

    /** Stop the sign-in in progress, ending the CLI's {@code auth login}: Cancel on the card. */
    record StopSignIn() {
        static final Topic<StopSignIn> TOPIC = Topic.edge(StopSignIn.class);
    }

    /** The sign-in page for attempt {@code attempt} is at {@code url}, for a browser that did not open. */
    record SignInLink(long attempt, String url) {
        static final Topic<SignInLink> TOPIC = Topic.edge(SignInLink.class);
    }

    /**
     * Sign-in attempt {@code attempt} ended: {@code stopped} before the CLI did, or the CLI ended saying it is
     * {@code signedIn}, or not, for the reason in {@code problem}.
     */
    record SignInEnded(long attempt, boolean stopped, boolean signedIn, String problem) {
        static final Topic<SignInEnded> TOPIC = Topic.edge(SignInEnded.class);
    }

    /**
     * Deliver strand {@code strand}'s queued messages to its Claude Code, starting the process if it is not running.
     * The messages stay on the strand's lane in the {@link Conductor}, in order; this says only whose to deliver.
     */
    record Deliver(String strand) {
        static final Topic<Deliver> TOPIC = Topic.edge(Deliver.class);
    }

    /** Write the settings store to disk: what is remembered between runs changed in memory. */
    record SaveSettings() {
        static final Topic<SaveSettings> TOPIC = Topic.edge(SaveSettings.class);
    }
}
