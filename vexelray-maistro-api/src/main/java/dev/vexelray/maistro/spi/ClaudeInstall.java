package dev.vexelray.maistro.spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Where the Claude Code CLI is installed on this operating system: everything Maistro's search for it needs that
 * differs per OS. One {@code vexelray-maistro-<os>} module provides it through {@link ServiceLoader}; the search itself
 * ({@code ClaudeCli}) names no OS. Every method takes the environment rather than reading it, so a search can be run
 * against a made-up one.
 */
public interface ClaudeInstall {

    /** The CLI's file name: {@code claude.exe}, {@code claude}. */
    String executableName();

    /** The directories of {@code PATH} in {@code env}, in order, as this OS writes and separates them. */
    List<String> pathEntries(Map<String, String> env);

    /** The user's home directory in {@code env}, as this OS names it, if set. */
    Optional<String> home(Map<String, String> env);

    /**
     * Where the Claude desktop app keeps its copies of the CLI ({@code <root>/<version>/<hash>/<executable>}), where
     * this OS has that app; empty where it does not.
     */
    Optional<Path> desktopAppRoot(Map<String, String> env);

    /** Whether {@code p} is a file this OS would run as the CLI. */
    default boolean isExecutableFile(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /** The one install layout on the class path. */
    static ClaudeInstall current() {
        List<ClaudeInstall> found = ServiceLoader.load(ClaudeInstall.class).stream().map(ServiceLoader.Provider::get)
                .toList();
        if (found.size() != 1) {
            throw new IllegalStateException((found.isEmpty() ? "no" : found.size() + "")
                    + " vexelray-maistro OS modules on the class path; the application's OS-activated profile adds one");
        }
        return found.getFirst();
    }
}
