package dev.vexelray.maistro.windows;

import dev.vexelray.maistro.spi.ClaudeInstall;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The Claude Code CLI on Windows: {@code claude.exe}, {@code %APPDATA%\Claude\claude-code}, {@code %USERPROFILE%}. */
public final class WindowsClaudeInstall implements ClaudeInstall {

    @Override
    public String executableName() {
        return "claude.exe";
    }

    /** {@code PATH}, or {@code Path} as Windows spells it, split on {@code ;}. */
    @Override
    public List<String> pathEntries(Map<String, String> env) {
        String path = nonBlank(env.get("PATH")).or(() -> nonBlank(env.get("Path"))).orElse(null);
        return path == null ? List.of() : Arrays.asList(path.split(";"));
    }

    @Override
    public Optional<String> home(Map<String, String> env) {
        return nonBlank(env.get("USERPROFILE")).or(() -> nonBlank(env.get("HOME")));
    }

    @Override
    public Optional<Path> desktopAppRoot(Map<String, String> env) {
        return nonBlank(env.get("APPDATA")).flatMap(a -> path(a.strip()))
                .map(a -> a.resolve("Claude").resolve("claude-code"));
    }

    /** An {@code .exe} needs no executable bit to run. */
    @Override
    public boolean isExecutableFile(Path p) {
        return Files.isRegularFile(p);
    }

    private static Optional<String> nonBlank(String s) {
        return s == null || s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    private static Optional<Path> path(String s) {
        try {
            return Optional.of(Path.of(s));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
