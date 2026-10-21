package dev.vexelray.maistro.agent;

import dev.vexelray.maistro.linux.LinuxClaudeInstall;
import dev.vexelray.maistro.spi.ClaudeInstall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The search for the CLI, against Linux's layout. */
class LinuxClaudeCliTest {

    private static final ClaudeInstall LINUX = new LinuxClaudeInstall();

    @TempDir
    Path tmp;

    private Path touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "exe");
        p.toFile().setExecutable(true);
        return p;
    }

    @Test
    void looksForPlainClaudeOnThePath() throws IOException {
        Path exe = touch(tmp.resolve("bin").resolve("claude"));
        touch(tmp.resolve("bin2").resolve("claude.exe"));
        ClaudeCli.Result r = ClaudeCli.locate(null, Map.of("PATH", tmp.resolve("bin2") + java.io.File.pathSeparator
                + exe.getParent(), "HOME", tmp.resolve("home").toString()), LINUX);
        assertEquals(exe.toAbsolutePath(), r.location().get().executable());
    }
}
