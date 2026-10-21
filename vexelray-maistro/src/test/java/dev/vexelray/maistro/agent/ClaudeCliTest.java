package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The real search, with this OS's module from the application's profile; the layouts are tested in those modules. */
class ClaudeCliTest {

    @Test
    void findsTheRealCliOnThisMachineIfInstalled() {
        // Not an assertion about the machine: only that the real search runs without throwing and explains itself.
        ClaudeCli.Result r = ClaudeCli.locate(null);
        assertFalse(r.reason().isEmpty());
    }
}
