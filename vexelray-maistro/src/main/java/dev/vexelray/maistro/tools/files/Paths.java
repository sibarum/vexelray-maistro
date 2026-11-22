package dev.vexelray.maistro.tools.files;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import dev.vexelray.maistro.spi.FileNames;

/**
 * A path the agent gave, as the file it names: absolute, normalized, or refused with the reason.
 *
 * <p>A path is absolute, or relative to the session's working folder. Nothing is expanded: no {@code ~}, no
 * {@code $VAR}, no glob, since no shell is in the way (agent-tools.md, principle 1). Which characters separate and
 * which names the system would open as something else are the OS's ({@link FileNames}): on Windows both slashes
 * separate, and a name Windows would silently change is refused, naming the part.
 *
 * @param names this OS's rules, {@link FileNames#current()} in the application
 * @param working the folder a relative path is taken from, absolute
 */
public record Paths(FileNames names, Path working) {

    public Paths {
        if (!working.isAbsolute()) {
            throw new IllegalArgumentException("the working folder is absolute, not " + working);
        }
    }

    /**
     * What a path resolved to.
     *
     * @param path    the absolute, normalized path; null when refused
     * @param problem why it was refused, in words; empty when it was not
     */
    public record Resolved(Path path, String problem) {

        public boolean ok() {
            return path != null;
        }

        static Resolved refused(String why) {
            return new Resolved(null, why);
        }
    }

    /** {@code given} as the file it names; refused when it is empty, holds a NUL, or is a name the OS would change. */
    public Resolved resolve(String given) {
        if (given.isEmpty()) {
            return Resolved.refused("the path is empty");
        }
        if (given.indexOf('\0') >= 0) {
            return Resolved.refused("the path holds a NUL character");
        }
        String p = names.separators(given);
        String problem = names.problem(p);
        if (!problem.isEmpty()) {
            return Resolved.refused(problem);
        }
        try {
            return new Resolved(working.resolve(p).toAbsolutePath().normalize(), "");
        } catch (InvalidPathException e) {
            return Resolved.refused("not a path here: " + e.getReason());
        }
    }

    /**
     * {@code path} as the agent should see it: relative to the working folder with forward slashes when it is
     * inside it, absolute otherwise. What the tools list and match by, so a result reads the same on every OS.
     */
    public String shown(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (abs.startsWith(working)) {
            String rel = working.relativize(abs).toString();
            return rel.isEmpty() ? "." : rel.replace(abs.getFileSystem().getSeparator(), "/");
        }
        return abs.toString();
    }
}
