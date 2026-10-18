package dev.vexelray.maistro.agent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import sibarum.probe.Log;

/**
 * Finds the Claude Code CLI executable.
 *
 * <p>In order, the first that exists wins:
 * <ol>
 *   <li>an explicit path (from a setting). When one is given, nothing else is tried: a setting that points at
 *       nothing is reported, not silently replaced by another copy;</li>
 *   <li>{@code claude.exe} (Windows) or {@code claude} (elsewhere) on {@code PATH};</li>
 *   <li>the newest copy the Claude desktop app keeps, {@code %APPDATA%\Claude\claude-code\<version>\<hash>\claude.exe}
 *       on Windows or {@code ~/Library/Application Support/Claude/claude-code/<version>/<hash>/claude} on macOS.
 *       Versions compare numerically by dotted segment ({@code 2.1.300} is newer than {@code 2.1.99});</li>
 *   <li>the native installer's {@code ~/.local/bin/claude[.exe]}.</li>
 * </ol>
 *
 * <p>An npm install's {@code claude.cmd} shim is deliberately not used: launching a batch file passes arguments
 * through {@code cmd.exe}'s quoting, which mangles JSON and prompts. Point the setting at the real executable instead.
 */
public final class ClaudeCli {

    private static final Log LOG = Log.of("vexelray-maistro.agent");

    private ClaudeCli() {
    }

    /** A found executable, and which rule found it (for the log and a settings page). */
    public record Location(Path executable, String source) {
    }

    /** The outcome of {@link #locate}: a location, or why there is none. {@code reason} is always readable text. */
    public record Result(Optional<Location> location, String reason) {

        static Result found(Path exe, String source) {
            return new Result(Optional.of(new Location(exe, source)), source);
        }

        static Result missing(String reason) {
            return new Result(Optional.empty(), reason);
        }

        public boolean isFound() {
            return location.isPresent();
        }
    }

    /** Locates the CLI using this process's environment. {@code explicitPath} may be null or blank for "not set". */
    public static Result locate(String explicitPath) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return locate(explicitPath, System.getenv(), windows);
    }

    /**
     * Locates the CLI against the given environment, so the search can be tested against a temporary tree.
     * Reads {@code PATH} (or {@code Path}), {@code APPDATA}, and {@code USERPROFILE} or {@code HOME}.
     */
    public static Result locate(String explicitPath, Map<String, String> env, boolean windows) {
        Result r = search(explicitPath, env, windows);
        if (r.isFound()) {
            LOG.info("claude CLI: {} ({})", r.location().get().executable(), r.reason());
        } else {
            LOG.warn("claude CLI not found: {}", r.reason());
        }
        return r;
    }

    private static Result search(String explicitPath, Map<String, String> env, boolean windows) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Optional<Path> p = path(explicitPath.strip());
            if (p.isPresent() && Files.isRegularFile(p.get())) {
                return Result.found(p.get().toAbsolutePath(), "the configured path");
            }
            return Result.missing("the configured path " + explicitPath.strip() + " is not a file");
        }
        String exeName = windows ? "claude.exe" : "claude";

        String pathVar = firstNonBlank(env.get("PATH"), env.get("Path"));
        if (pathVar != null) {
            String sep = windows ? ";" : File.pathSeparator;
            for (String dir : pathVar.split(sep)) {
                String d = stripQuotes(dir.strip());
                if (d.isEmpty()) {
                    continue;
                }
                Optional<Path> candidate = path(d).map(x -> x.resolve(exeName));
                if (candidate.isPresent() && isExecutableFile(candidate.get(), windows)) {
                    return Result.found(candidate.get().toAbsolutePath(), "PATH");
                }
            }
        }

        String home = firstNonBlank(windows ? env.get("USERPROFILE") : null, env.get("HOME"), env.get("USERPROFILE"));
        Optional<Path> appRoot = Optional.empty();
        if (windows) {
            String appData = env.get("APPDATA");
            if (appData != null && !appData.isBlank()) {
                appRoot = path(appData.strip()).map(a -> a.resolve("Claude").resolve("claude-code"));
            }
        } else if (home != null) {
            appRoot = path(home).map(h -> h.resolve("Library").resolve("Application Support").resolve("Claude")
                    .resolve("claude-code"));
        }
        if (appRoot.isPresent()) {
            Optional<Path> newest = newestDesktopCopy(appRoot.get(), exeName);
            if (newest.isPresent()) {
                return Result.found(newest.get().toAbsolutePath(), "the Claude desktop app's copy");
            }
        }

        if (home != null) {
            Optional<Path> local = path(home).map(h -> h.resolve(".local").resolve("bin").resolve(exeName));
            if (local.isPresent() && isExecutableFile(local.get(), windows)) {
                return Result.found(local.get().toAbsolutePath(), "~/.local/bin");
            }
        }

        return Result.missing(exeName + " is not on PATH, and no copy was found under "
                + appRoot.map(Path::toString).orElse("the Claude desktop app's folder")
                + " or ~/.local/bin; set its path in the settings");
    }

    /** The executable in the highest-versioned {@code <root>/<version>/<hash>/} that has one. */
    static Optional<Path> newestDesktopCopy(Path root, String exeName) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        List<Path> versions = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).filter(p -> parseVersion(p.getFileName().toString()) != null)
                    .forEach(versions::add);
        } catch (IOException e) {
            LOG.debug("could not list " + root, e);
            return Optional.empty();
        }
        versions.sort(Comparator.comparing((Path p) -> parseVersion(p.getFileName().toString()),
                ClaudeCli::compareVersions).reversed());
        for (Path v : versions) {
            List<Path> exes = new ArrayList<>();
            try (Stream<Path> s = Files.list(v)) {
                s.filter(Files::isDirectory).map(h -> h.resolve(exeName)).filter(Files::isRegularFile)
                        .forEach(exes::add);
            } catch (IOException e) {
                LOG.debug("could not list " + v, e);
                continue;
            }
            if (!exes.isEmpty()) {
                exes.sort(Comparator.comparingLong(ClaudeCli::modified).reversed());
                return Optional.of(exes.getFirst());
            }
        }
        return Optional.empty();
    }

    /**
     * The numeric segments of a dotted version ({@code "2.1.299"} is {@code [2, 1, 299]}), or null when the name is
     * not one. A pre-release suffix on the last segment ({@code "2.1.0-beta"}) is ignored.
     */
    static long[] parseVersion(String name) {
        String core = name;
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.", -1);
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 18) {
                return null;
            }
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            out[i] = Long.parseLong(p);
        }
        return out;
    }

    /** Compares two parsed versions segment by segment; a missing segment counts as 0. */
    static int compareVersions(long[] a, long[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isExecutableFile(Path p, boolean windows) {
        return Files.isRegularFile(p) && (windows || Files.isExecutable(p));
    }

    private static Optional<Path> path(String s) {
        try {
            return Optional.of(Path.of(s));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
