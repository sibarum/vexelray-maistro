package dev.vexelray.maistro.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The running CLI, as {@link AgentSession} sees it: three pipes, a wait and a kill. The seam that lets tests feed
 * recorded lines to a session without starting anything; {@link #SYSTEM} is the real launcher.
 */
public interface AgentProcess {

    OutputStream stdin();

    InputStream stdout();

    InputStream stderr();

    /** Waits up to {@code timeout} for the process to end; true if it has. */
    boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

    /** The exit code; only meaningful once {@link #waitFor} returned true. */
    int exitCode();

    /** Ends the process and every process it started. */
    void destroyTree();

    /** Starts a process. */
    @FunctionalInterface
    interface Launcher {
        /**
         * @param command     the executable and its arguments
         * @param workingDir  the directory to run in, or null for this process's
         * @param environment the child's full environment
         */
        AgentProcess launch(List<String> command, Path workingDir, Map<String, String> environment) throws IOException;
    }

    /**
     * {@code command} as {@link ProcessBuilder} must be given it on Windows for the program to read back the arguments
     * it was meant to have.
     *
     * <p>Windows has no argument list, only a command line, which the program splits again (the C runtime's rules:
     * {@code \"} is a quote, and backslashes are literal except in a run before a quote, where each pair is one).
     * The JDK, in its default mode ({@code jdk.lang.Process.allowAmbiguousCommands} not {@code false}), wraps an
     * argument with whitespace in quotes and doubles its trailing backslashes, but passes a double quote inside it
     * as it is, so the program reads it as the end or start of a quoted stretch and drops it. So each quote, and the
     * backslashes before it, are escaped here, and the JDK's wrapping is left to do the rest: an escaped argument
     * never starts with a quote, so the JDK never takes it for one already quoted.
     *
     * <p>Not for a {@code .cmd} or {@code .bat}, which {@code cmd.exe} reads by rules of its own, where {@code \"} is
     * no escape; nor when {@code allowAmbiguousCommands} is {@code false}, where the JDK escapes quotes itself.
     *
     * @param legacy whether the JDK is in its default mode, {@code allowAmbiguousCommands} not {@code false}
     */
    static List<String> forWindows(List<String> command, boolean legacy) {
        if (!legacy || command.isEmpty()) {
            return command;
        }
        String exe = command.getFirst().toLowerCase(java.util.Locale.ROOT);
        if (exe.endsWith(".cmd") || exe.endsWith(".bat")) {
            return command;
        }
        List<String> out = new java.util.ArrayList<>(command.size());
        out.add(command.getFirst());
        for (int i = 1; i < command.size(); i++) {
            out.add(escapeQuotes(command.get(i)));
        }
        return out;
    }

    /** {@code arg} with each double quote as {@code \"}, and the backslashes just before it doubled. */
    static String escapeQuotes(String arg) {
        if (arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder sb = new StringBuilder(arg.length() + 8);
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            sb.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes));
            backslashes = 0;
            sb.append(c);
        }
        sb.append("\\".repeat(backslashes));
        return sb.toString();
    }

    /** Whether this is Windows, where {@link #SYSTEM} hands its command to {@link #forWindows} first. */
    boolean WINDOWS = java.io.File.separatorChar == '\\';

    /** Launches a real process with {@link ProcessBuilder}. */
    Launcher SYSTEM = (command, workingDir, environment) -> {
        ProcessBuilder pb = new ProcessBuilder(!WINDOWS ? command : forWindows(command,
                !"false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"))));
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(environment);
        Process p = pb.start();
        return new AgentProcess() {
            @Override
            public OutputStream stdin() {
                return p.getOutputStream();
            }

            @Override
            public InputStream stdout() {
                return p.getInputStream();
            }

            @Override
            public InputStream stderr() {
                return p.getErrorStream();
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                return p.waitFor(timeout, unit);
            }

            @Override
            public int exitCode() {
                return p.exitValue();
            }

            @Override
            public void destroyTree() {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        };
    };
}
