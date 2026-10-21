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

    /** Launches a real process with {@link ProcessBuilder}. */
    Launcher SYSTEM = (command, workingDir, environment) -> {
        ProcessBuilder pb = new ProcessBuilder(command);
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
