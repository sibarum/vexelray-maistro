package dev.vexelray.maistro.macos;

import dev.vexelray.maistro.spi.ClaudeInstall;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The Claude Code CLI on macOS: {@code claude}, {@code $HOME}, and the Claude desktop app's copies under {@code ~/Library/Application Support/Claude/claude-code}. */
public final class MacosClaudeInstall implements ClaudeInstall {

    @Override
    public String executableName() {
        return "claude";
    }

    @Override
    public List<String> pathEntries(Map<String, String> env) {
        String path = env.get("PATH");
        return path == null || path.isBlank() ? List.of() : Arrays.asList(path.split(File.pathSeparator));
    }

    @Override
    public Optional<String> home(Map<String, String> env) {
        String home = env.get("HOME");
        return home == null || home.isBlank() ? Optional.empty() : Optional.of(home);
    }

    @Override
    public Optional<Path> desktopAppRoot(Map<String, String> env) {
        return home(env).flatMap(h -> {
            try {
                return Optional.of(Path.of(h).resolve("Library").resolve("Application Support").resolve("Claude")
                        .resolve("claude-code"));
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        });
    }
}
