package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeCliTest {

    @TempDir
    Path tmp;

    private Path touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "exe");
        p.toFile().setExecutable(true);
        return p;
    }

    private Path appData() {
        return tmp.resolve("AppData").resolve("Roaming");
    }

    private Path desktopCopy(String version, String hash) throws IOException {
        return touch(appData().resolve("Claude").resolve("claude-code").resolve(version).resolve(hash)
                .resolve("claude.exe"));
    }

    @Test
    void explicitPathWins() throws IOException {
        Path exe = touch(tmp.resolve("custom").resolve("my-claude.exe"));
        Path onPath = touch(tmp.resolve("bin").resolve("claude.exe"));
        ClaudeCli.Result r = ClaudeCli.locate(exe.toString(), Map.of("PATH", onPath.getParent().toString()), true);
        assertTrue(r.isFound());
        assertEquals(exe.toAbsolutePath(), r.location().get().executable());
        assertEquals("the configured path", r.location().get().source());
    }

    @Test
    void explicitPathThatIsMissingIsReportedNotReplaced() throws IOException {
        Path onPath = touch(tmp.resolve("bin").resolve("claude.exe"));
        ClaudeCli.Result r = ClaudeCli.locate(tmp.resolve("nope.exe").toString(),
                Map.of("PATH", onPath.getParent().toString()), true);
        assertFalse(r.isFound());
        assertTrue(r.reason().contains("nope.exe"), r.reason());
    }

    @Test
    void pathIsSearchedInOrder() throws IOException {
        Path first = touch(tmp.resolve("a").resolve("claude.exe"));
        touch(tmp.resolve("b").resolve("claude.exe"));
        String path = "\"" + tmp.resolve("empty") + "\";;" + first.getParent() + ";" + tmp.resolve("b");
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("Path", path), true);
        assertEquals(first.toAbsolutePath(), r.location().get().executable());
        assertEquals("PATH", r.reason());
    }

    @Test
    void pathBeatsTheDesktopCopy() throws IOException {
        Path onPath = touch(tmp.resolve("bin").resolve("claude.exe"));
        desktopCopy("2.1.299", "f0584be0fbaf");
        ClaudeCli.Result r = ClaudeCli.locate("  ", Map.of("PATH", onPath.getParent().toString(),
                "APPDATA", appData().toString()), true);
        assertEquals(onPath.toAbsolutePath(), r.location().get().executable());
    }

    @Test
    void newestDesktopCopyByNumericVersion() throws IOException {
        desktopCopy("2.1.295", "d2e29494ec83");
        desktopCopy("2.1.299", "f0584be0fbaf");
        Path newest = desktopCopy("10.0", "x");
        // not versions, or versions with no executable: skipped
        touch(appData().resolve("Claude").resolve("claude-code").resolve("latest").resolve("h").resolve("claude.exe"));
        Files.createDirectories(appData().resolve("Claude").resolve("claude-code").resolve("11.0").resolve("empty"));
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("APPDATA", appData().toString()), true);
        assertEquals(newest.toAbsolutePath(), r.location().get().executable());
        assertEquals("the Claude desktop app's copy", r.reason());
    }

    @Test
    void lexicallySmallerButNumericallyNewerVersionWins() throws IOException {
        desktopCopy("2.1.99", "old");
        Path newer = desktopCopy("2.1.100", "new");
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("APPDATA", appData().toString()), true);
        assertEquals(newer.toAbsolutePath(), r.location().get().executable());
    }

    @Test
    void newestHashFolderWhenAVersionHasSeveral() throws IOException {
        Path older = desktopCopy("2.1.299", "aaa");
        Path newer = desktopCopy("2.1.299", "bbb");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000));
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("APPDATA", appData().toString()), true);
        assertEquals(newer.toAbsolutePath(), r.location().get().executable());
    }

    @Test
    void localBinIsTheLastResort() throws IOException {
        Path local = touch(tmp.resolve("home").resolve(".local").resolve("bin").resolve("claude.exe"));
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("USERPROFILE", tmp.resolve("home").toString(),
                "APPDATA", appData().toString()), true);
        assertEquals(local.toAbsolutePath(), r.location().get().executable());
    }

    @Test
    void nothingFoundSaysWhereItLooked() {
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("PATH", tmp.toString(), "APPDATA", appData().toString()),
                true);
        assertFalse(r.isFound());
        assertTrue(r.reason().contains("claude.exe"), r.reason());
        assertTrue(r.reason().contains("claude-code"), r.reason());
    }

    @Test
    void nonWindowsLooksForPlainClaude() throws IOException {
        Path exe = touch(tmp.resolve("bin").resolve("claude"));
        touch(tmp.resolve("bin2").resolve("claude.exe"));
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("PATH", tmp.resolve("bin2") + java.io.File.pathSeparator
                + exe.getParent(), "HOME", tmp.resolve("home").toString()), false);
        assertEquals(exe.toAbsolutePath(), r.location().get().executable());
    }

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

    @Test
    void findsTheRealCliOnThisMachineIfInstalled() {
        // Not an assertion about the machine: only that the real search runs without throwing and explains itself.
        ClaudeCli.Result r = ClaudeCli.locate(null);
        assertFalse(r.reason().isEmpty());
    }
}
