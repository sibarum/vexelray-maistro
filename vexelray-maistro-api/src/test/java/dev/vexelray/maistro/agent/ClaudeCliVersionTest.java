package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** How the desktop app's version folders are read and ordered: no OS in it. */
class ClaudeCliVersionTest {

    @Test
    void versionParsingAndOrder() {
        assertEquals(0, ClaudeCli.compareVersions(ClaudeCli.parseVersion("2.1"), ClaudeCli.parseVersion("2.1.0")));
        assertTrue(ClaudeCli.compareVersions(ClaudeCli.parseVersion("2.1.300"), ClaudeCli.parseVersion("2.1.299")) > 0);
        assertTrue(ClaudeCli.compareVersions(ClaudeCli.parseVersion("2.10.0"), ClaudeCli.parseVersion("2.9.9")) > 0);
        assertTrue(ClaudeCli.compareVersions(ClaudeCli.parseVersion("2.1.0-beta"), ClaudeCli.parseVersion("2.0")) > 0);
        assertNull(ClaudeCli.parseVersion("latest"));
        assertNull(ClaudeCli.parseVersion("2..1"));
        assertNull(ClaudeCli.parseVersion(""));
        assertNull(ClaudeCli.parseVersion("2.x"));
    }
}
