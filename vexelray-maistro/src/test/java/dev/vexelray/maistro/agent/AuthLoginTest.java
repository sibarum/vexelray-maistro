package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AuthLoginTest {

    /**
     * What claude.exe 2.1.330 prints to {@code auth login} when its output is a pipe: the lead, the page's address on
     * a line of its own, and, once the browser has called it back, the end. Its strings, as they are in the binary;
     * the address is made up.
     */
    static final String SIGNED_IN = """
            Opening browser to sign in…
            If the browser didn't open, visit:
            https://claude.ai/oauth/authorize?code=true&client_id=x&state=y
            Login successful.
            """;

    /** A CLI that has said everything it will say once its output is read, and has then ended. */
    static class Said implements AgentProcess {
        final InputStream out;
        final InputStream err;
        final int exit;
        volatile boolean stdinClosed;
        volatile boolean destroyed;

        Said(String out, String err, int exit) {
            this.out = new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
            this.err = new ByteArrayInputStream(err.getBytes(StandardCharsets.UTF_8));
            this.exit = exit;
        }

        @Override
        public OutputStream stdin() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    stdinClosed = true;
                }
            };
        }

        @Override
        public InputStream stdout() {
            return out;
        }

        @Override
        public InputStream stderr() {
            return err;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            try {
                return out.available() == 0 && err.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public int exitCode() {
            return exit;
        }

        @Override
        public void destroyTree() {
            destroyed = true;
        }
    }

    private static AuthLogin.Ended run(AgentProcess p, List<String> links, AtomicReference<List<String>> command) {
        return AuthLogin.run(Path.of("claude.exe"), AuthLogin.Method.SSO, Map.of(), (c, dir, env) -> {
            command.set(c);
            return p;
        }, started -> { }, links::add);
    }

    @Test
    void theCommandNamesTheAccountToSignInWith() {
        assertEquals(List.of("claude.exe", "auth", "login", "--claudeai"),
                AuthLogin.command(Path.of("claude.exe"), AuthLogin.Method.SUBSCRIPTION));
        assertEquals("--console", AuthLogin.Method.CONSOLE.flag());
        assertEquals("--sso", AuthLogin.Method.SSO.flag());
    }

    @Test
    void theLinkIsTheAddressOnItsLine() {
        assertEquals("https://claude.ai/x?y=1", AuthLogin.link("https://claude.ai/x?y=1"));
        assertEquals("https://claude.ai/x", AuthLogin.link("  visit: https://claude.ai/x  then come back"));
        assertEquals("", AuthLogin.link("If the browser didn't open, visit:"));
        assertEquals("", AuthLogin.link("http://localhost:1234/callback"), "not a page to sign in on");
    }

    @Test
    void aSignInSaysItsLinkAndEndsSignedIn() {
        Said cli = new Said(SIGNED_IN, "", 0);
        List<String> links = new CopyOnWriteArrayList<>();
        AtomicReference<List<String>> command = new AtomicReference<>();
        AuthLogin.Ended e = run(cli, links, command);
        assertEquals(List.of("claude.exe", "auth", "login", "--sso"), command.get());
        assertTrue(cli.stdinClosed, "it is given nothing to read, so it never waits on a pasted code");
        assertEquals(List.of("https://claude.ai/oauth/authorize?code=true&client_id=x&state=y"), links,
                "said once");
        assertTrue(e.signedIn());
        assertFalse(e.stopped());
        assertEquals("", e.detail());
    }

    @Test
    void aLastLineWithNoEndIsStillRead() {
        List<String> links = new CopyOnWriteArrayList<>();
        AuthLogin.Ended e = run(new Said("visit:\nhttps://claude.ai/a", "", 0), links, new AtomicReference<>());
        assertEquals(List.of("https://claude.ai/a"), links);
        assertTrue(e.signedIn());
    }

    @Test
    void aFailureSaysWhyInTheClisWords() {
        AuthLogin.Ended e = run(new Said("Opening browser to sign in…\n", "Couldn't log in: timed out\n", 1),
                new CopyOnWriteArrayList<>(), new AtomicReference<>());
        assertFalse(e.signedIn());
        assertFalse(e.stopped());
        assertEquals("Couldn't log in: timed out", e.detail());
        AuthLogin.Ended silent = run(new Said("", "", 3), new CopyOnWriteArrayList<>(), new AtomicReference<>());
        assertEquals("Claude Code's sign-in ended with exit code 3", silent.detail());
    }

    @Test
    void aCliThatCannotBeRunIsAFailureNotAThrow() {
        AuthLogin.Ended e = AuthLogin.run(Path.of("claude.exe"), AuthLogin.Method.SUBSCRIPTION, Map.of(),
                (c, dir, env) -> {
                    throw new IOException("no such file");
                }, started -> { }, link -> { });
        assertFalse(e.signedIn());
        assertTrue(e.detail().contains("no such file"));
    }

    @Test
    void stoppingTheRunEndsTheCli() throws Exception {
        Said waiting = new Said("", "", 0) {
            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return destroyed;
            }
        };
        CountDownLatch running = new CountDownLatch(1);
        AtomicReference<AgentProcess> handed = new AtomicReference<>();
        AtomicReference<AuthLogin.Ended> ended = new AtomicReference<>();
        Thread run = new Thread(() -> ended.set(AuthLogin.run(Path.of("claude.exe"), AuthLogin.Method.SUBSCRIPTION,
                Map.of(), (c, dir, env) -> waiting, p -> {
                    handed.set(p);
                    running.countDown();
                }, link -> { })));
        run.start();
        running.await();
        run.interrupt();
        run.join();
        assertTrue(ended.get().stopped());
        assertFalse(ended.get().signedIn());
        assertTrue(waiting.destroyed, "the CLI, and what it started, are ended");
        assertTrue(handed.get() == waiting, "whoever stops the run is handed the process, to end it too");
    }
}
