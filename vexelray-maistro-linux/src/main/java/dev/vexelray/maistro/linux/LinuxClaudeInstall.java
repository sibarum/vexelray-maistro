package dev.vexelray.maistro.linux;

import dev.vexelray.maistro.spi.ClaudeInstall;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The Claude Code CLI on Linux: {@code claude}, {@code $HOME}. */
public final class LinuxClaudeInstall implements ClaudeInstall {

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

    /** There is no Claude desktop app on Linux to keep a copy. */
    @Override
    public Optional<Path> desktopAppRoot(Map<String, String> env) {
        return Optional.empty();
    }
}
