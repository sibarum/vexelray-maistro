package dev.vexelray.maistro.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import sibarum.probe.Log;

/**
 * Signing a Claude Code CLI in, in the browser, as {@code claude auth login} does it.
 *
 * <p><b>What the CLI does.</b> It opens the sign-in page in the default browser and waits for the page to call it back
 * on localhost. Its output is a few lines: {@code Opening browser to sign in…}, then {@code If the browser didn't open,
 * visit:} and the page's address on a line of its own, then {@code Login successful.} and exit 0. Asked through pipes
 * rather than a terminal it never asks for a pasted code (version 2.1.330: its paste prompt returns at once unless both
 * its input and output are terminals), so nothing is written to it. The credentials it saves are in its config
 * directory, shared by every copy of the CLI on the machine, which is why Maistro has no sign-in of its own.
 *
 * <p><b>Why its output is looked at rather than read.</b> The browser the CLI starts may inherit its output pipe, and
 * then a read waits for the browser to close as well as the CLI. So {@link #run} reads only what is there, and between
 * looks asks whether the CLI has ended, as vexelray-inspector's {@code Child} does for the same reason. The waits are
 * {@code Thread.sleep}, which an interrupt ends: that is how the run is stopped.
 */
public final class AuthLogin {

    private static final Log LOG = Log.of("vexelray-maistro.agent");

    /** How long a run waits between looks at the CLI's output. */
    static final long LOOK_MS = 50;

    private AuthLogin() {
    }

    /** Which account to sign in with: the CLI's own choices, one flag each. */
    public enum Method {
        /** A Claude subscription, the CLI's default. */
        SUBSCRIPTION("--claudeai"),
        /** An Anthropic Console account, billed by API usage. */
        CONSOLE("--console"),
        /** The organisation's single sign-on. */
        SSO("--sso");

        private final String flag;

        Method(String flag) {
            this.flag = flag;
        }

        /** The CLI's flag for it. */
        public String flag() {
            return flag;
        }
    }

    /**
     * How a run ended.
     *
     * @param stopped  the run was stopped before the CLI ended (the CLI and what it started were ended)
     * @param signedIn the CLI ended saying so: exit 0
     * @param detail   when neither, why, in the CLI's words where it gave any; empty otherwise
     */
    public record Ended(boolean stopped, boolean signedIn, String detail) {

        static Ended failed(String detail) {
            return new Ended(false, false, detail);
        }
    }

    /** {@code <exe> auth login <flag>}. */
    public static List<String> command(Path executable, Method method) {
        return List.of(executable.toString(), "auth", "login", method.flag());
    }

    /**
     * The sign-in page's address in {@code line} of the CLI's output, or empty when it has none. The address is a
     * line of its own, but a CLI that put it after its lead (<code>visit: https://...</code>) is read the same way.
     */
    public static String link(String line) {
        int at = line.indexOf("https://");
        if (at < 0) {
            return "";
        }
        int end = at;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return line.substring(at, end);
    }

    /**
     * Sign {@code executable} in with {@code method}, with {@code environment} (built as for a session). Blocks until
     * the CLI ends or the calling thread is interrupted, for as long as the person takes in the browser: call it on a
     * thread of its own. Never throws.
     *
     * @param started told the process once it is running, so whoever stops the run can end it too
     * @param linked  told the sign-in page's address, once, as soon as the CLI prints it
     */
    public static Ended run(Path executable, Method method, Map<String, String> environment,
                            AgentProcess.Launcher launcher, Consumer<AgentProcess> started, Consumer<String> linked) {
        AgentProcess p;
        try {
            p = launcher.launch(command(executable, method), null, environment);
        } catch (IOException e) {
            LOG.warn("could not run " + executable + " auth login", e);
            return Ended.failed("Claude Code could not be run: " + e.getMessage());
        }
        started.accept(p);
        try {
            p.stdin().close();
        } catch (IOException ignored) {
            // it reads nothing through pipes; a pipe already gone is fine
        }
        Lines out = new Lines(p.stdout());
        Lines err = new Lines(p.stderr());
        String link = "";
        try {
            while (true) {
                boolean ended = p.waitFor(0, TimeUnit.MILLISECONDS);
                // Read after asking, so what the CLI wrote before it ended is all read before the loop stops.
                if (ended) {
                    out.finish();
                    err.finish();
                }
                boolean read = false;
                for (String line = out.next(); line != null; line = out.next()) {
                    read = true;
                    LOG.debug("auth login: {}", line);
                    if (link.isEmpty() && !link(line).isEmpty()) {
                        link = link(line);
                        linked.accept(link);
                    }
                }
                for (String line = err.next(); line != null; line = err.next()) {
                    read = true;
                    LOG.debug("auth login (stderr): {}", line);
                }
                if (ended) {
                    break;
                }
                if (!read) {
                    Thread.sleep(LOOK_MS);
                }
            }
        } catch (InterruptedException e) {
            p.destroyTree();
            LOG.info("{} auth login stopped", executable);
            return new Ended(true, false, "");
        } catch (IOException e) {
            p.destroyTree();
            LOG.warn("could not read " + executable + " auth login", e);
            return Ended.failed("Claude Code's sign-in could not be read");
        }
        int exit = p.exitCode();
        LOG.info("{} auth login: exit {}", executable, exit);
        if (exit == 0) {
            return new Ended(false, true, "");
        }
        String last = !err.last().isEmpty() ? err.last() : out.last();
        return Ended.failed(last.isEmpty() ? "Claude Code's sign-in ended with exit code " + exit : last);
    }

    /** One of the CLI's output streams, split into lines from only what is there to read. */
    private static final class Lines {

        private final InputStream in;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final ArrayDeque<String> ready = new ArrayDeque<>();
        private String last = "";

        Lines(InputStream in) {
            this.in = in;
        }

        /** The next whole line, or null when none is there yet. Never waits. */
        String next() throws IOException {
            while (ready.isEmpty() && in.available() > 0) {
                int c = in.read();
                if (c < 0) {
                    break;
                }
                take(c);
            }
            return ready.poll();
        }

        /** The CLI has ended: take the rest of what it wrote, a last line with no end among it, for {@link #next}. */
        void finish() throws IOException {
            while (in.available() > 0) {
                int c = in.read();
                if (c < 0) {
                    break;
                }
                take(c);
            }
            end();
        }

        /** The last line that was not blank. */
        String last() {
            return last;
        }

        private void take(int c) {
            if (c == '\n' || c == '\r') {
                end();
            } else {
                line.write(c);
            }
        }

        private void end() {
            String s = line.toString(StandardCharsets.UTF_8).strip();
            line.reset();
            if (!s.isEmpty()) {
                last = s;
                ready.add(s);
            }
        }
    }
}
