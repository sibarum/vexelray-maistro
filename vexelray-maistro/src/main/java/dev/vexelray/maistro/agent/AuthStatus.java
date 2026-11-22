package dev.vexelray.maistro.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import sibarum.probe.Log;

/**
 * Whether a Claude Code CLI is signed in, as {@code claude auth status --json} says.
 *
 * <p>The CLI keeps its credentials in its config directory ({@code ~/.claude}), shared by every copy on the machine,
 * and answers without its terminal UI and without the network. Signed out, version 2.1.304 prints
 * {@code {"loggedIn": false, "authMethod": "none", ...}} and exits with 1. Signed in, 2.1.330 prints
 * {@code {"loggedIn": true, "authMethod": "claude.ai", ...}} and exits with 0, with the account's email, organisation,
 * subscription and expiry beside them ({@code src/test/resources/agent/real-auth-status-signed-in.json}). Only
 * {@code loggedIn} and {@code authMethod} are read.
 *
 * @param signedIn what {@code loggedIn} said; meaningless when {@link #known} is false
 * @param known    the CLI answered in that shape. False for an older CLI without {@code auth}, one that did not
 *                 answer in time, or one that could not be run: then nothing is known, and nothing should be held
 *                 back on its account
 * @param method   {@code authMethod} as the CLI wrote it ({@code none}, ...); empty when not given
 * @param detail   why it is not known, in words, for the log and the status line; empty when known
 */
public record AuthStatus(boolean signedIn, boolean known, String method, String detail) {

    private static final Log LOG = Log.of("vexelray-maistro.agent");

    /** How long the CLI is given to answer before it is ended and nothing is known. */
    public static final long TIMEOUT_MS = 15_000;

    /** {@code <exe> auth status --json}. */
    public static List<String> command(Path executable) {
        return List.of(executable.toString(), "auth", "status", "--json");
    }

    /** Nothing is known, for {@code why}. */
    public static AuthStatus unknown(String why) {
        return new AuthStatus(false, false, "", why);
    }

    /**
     * What {@code stdout} says. The JSON is taken from its first <code>{</code> to its last <code>}</code>, so a
     * line the CLI writes around it does not hide it; the exit code is not used, since signed out exits with 1.
     */
    public static AuthStatus parse(String stdout) {
        int start = stdout.indexOf('{');
        int end = stdout.lastIndexOf('}');
        if (start < 0 || end < start) {
            return unknown("Claude Code did not say whether it is signed in");
        }
        JsonValue v;
        try {
            v = Json.parse(stdout.substring(start, end + 1));
        } catch (JsonException e) {
            return unknown("Claude Code's sign-in status could not be read");
        }
        if (!v.get("loggedIn").isBool()) {
            return unknown("Claude Code's sign-in status could not be read");
        }
        return new AuthStatus(v.get("loggedIn").asBool(false), true, v.get("authMethod").asString(), "");
    }

    /**
     * Ask {@code executable}, with {@code environment} (built as for a session, host variables removed). Blocks for
     * up to {@link #TIMEOUT_MS}: call it on a lane meant for waiting. Never throws; a failure is an unknown status.
     */
    public static AuthStatus check(Path executable, Map<String, String> environment, AgentProcess.Launcher launcher) {
        AgentProcess p;
        try {
            p = launcher.launch(command(executable), null, environment);
        } catch (IOException e) {
            LOG.warn("could not run " + executable + " auth status", e);
            return unknown("Claude Code could not be run: " + e.getMessage());
        }
        try {
            p.stdin().close();
        } catch (IOException ignored) {
            // it reads nothing; a pipe already gone is fine
        }
        try {
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyTree();
                LOG.warn("{} auth status did not answer in {} ms", executable, TIMEOUT_MS);
                return unknown("Claude Code did not say whether it is signed in");
            }
            // The answer is a few hundred bytes, so it fits the pipe and the process could end before it is read.
            String out = new String(p.stdout().readAllBytes(), StandardCharsets.UTF_8);
            AuthStatus s = parse(out);
            LOG.info("{} auth status: exit {}, {}", executable, p.exitCode(), s.known()
                    ? (s.signedIn() ? "signed in (" + s.method() + ")" : "signed out") : s.detail());
            if (!s.known()) {
                LOG.debug("auth status said: {}", out.strip());
            }
            return s;
        } catch (InterruptedException e) {
            p.destroyTree();
            Thread.currentThread().interrupt();
            return unknown("the sign-in check was interrupted");
        } catch (IOException e) {
            LOG.warn("could not read " + executable + " auth status", e);
            return unknown("Claude Code's sign-in status could not be read");
        }
    }
}
