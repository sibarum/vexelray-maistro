package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AuthStatusTest {

    /** What claude.exe 2.1.304 printed, signed out (exit code 1), on this machine. */
    static final String SIGNED_OUT = """
            {
              "loggedIn": false,
              "authMethod": "none",
              "apiProvider": "firstParty",
              "analyticsDisabled": false,
              "projectsDirectory": "C:\\\\Users\\\\User\\\\.claude\\\\projects",
              "configDirectory": "C:\\\\Users\\\\User\\\\.claude"
            }
            """;

    @Test
    void signedOutAsTheCliSaysIt() {
        AuthStatus s = AuthStatus.parse(SIGNED_OUT);
        assertTrue(s.known());
        assertFalse(s.signedIn());
        assertEquals("none", s.method());
    }

    @Test
    void signedInReadsTheMethod() {
        AuthStatus s = AuthStatus.parse("{\"loggedIn\": true, \"authMethod\": \"claude.ai\"}");
        assertTrue(s.known());
        assertTrue(s.signedIn());
        assertEquals("claude.ai", s.method());
    }

    @Test
    void aLineAroundTheJsonDoesNotHideIt() {
        assertTrue(AuthStatus.parse("warning: something\n" + SIGNED_OUT + "\n").known());
    }

    @Test
    void anythingElseIsUnknown() {
        assertFalse(AuthStatus.parse("").known());
        assertFalse(AuthStatus.parse("error: unknown command 'auth'").known());
        assertFalse(AuthStatus.parse("{\"loggedIn\": \"yes\"}").known(), "not a boolean");
        assertFalse(AuthStatus.parse("{nope}").known());
        assertFalse(AuthStatus.parse("").detail().isEmpty(), "an unknown says why");
    }

    @Test
    void checkRunsAuthStatusWithItsInputClosed() throws Exception {
        AgentSessionTest.FakeProcess fake = new AgentSessionTest.FakeProcess();
        AtomicReference<List<String>> ran = new AtomicReference<>();
        fake.out.feed(SIGNED_OUT);
        fake.exit(1);
        AuthStatus s = AuthStatus.check(Path.of("claude.exe"), Map.of(), (command, dir, env) -> {
            ran.set(command);
            return fake;
        });
        assertEquals(List.of("claude.exe", "auth", "status", "--json"), ran.get());
        assertTrue(fake.stdinClosed);
        assertTrue(s.known());
        assertFalse(s.signedIn());
    }

    @Test
    void aCliThatCannotBeRunIsUnknown() {
        AuthStatus s = AuthStatus.check(Path.of("claude.exe"), Map.of(), (command, dir, env) -> {
            throw new java.io.IOException("no such file");
        });
        assertFalse(s.known());
        assertTrue(s.detail().contains("no such file"), s.detail());
    }
}
