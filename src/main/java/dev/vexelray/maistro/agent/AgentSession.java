package dev.vexelray.maistro.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import sibarum.probe.Log;

/**
 * One conversation with the Claude Code CLI, running as
 * {@code claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages}.
 *
 * <p>{@link #start} launches the process; {@link #send} writes a user turn to its stdin, and further turns go to the
 * same stdin while it runs. The CLI writes nothing until the first turn arrives; it then sends
 * {@link AgentEvent.SessionInit} and the turn's events, ending with {@link AgentEvent.Result}, and waits for the next
 * turn. {@link #interrupt} stops the turn in progress and keeps the process; {@link #close} ends the process.
 *
 * <p><b>Threading.</b> Two daemon threads per session: one reads stdout, parses each line and calls the
 * {@link AgentListener} (so every listener call is on that thread, in order, ending with exactly one
 * {@link AgentEvent.ProcessExit}); the other copies stderr to the log. {@link #send}, {@link #interrupt} and
 * {@link #close} may be called from any thread, including the listener's; writes to stdin are serialized. They
 * block only for the pipe write, except {@code close}, which waits up to about three seconds for the process to end
 * (and does not wait for the reader when called from it).
 */
public final class AgentSession implements AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.agent");

    /**
     * Variables a Claude Code host sets for the CLIs it runs. When Maistro is itself started from inside a Claude Code
     * session they would be inherited and make the child believe it is a nested, host-managed session, so they are
     * removed from the child's environment by default ({@link Options#scrubHostVariables()}).
     */
    public static final Set<String> HOST_SESSION_VARIABLES = Set.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
            "CLAUDE_CODE_HOST_SESSION_ID", "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN",
            "CLAUDE_CODE_SDK_HAS_HOST_AUTH_REFRESH", "CLAUDE_AGENT_SDK_VERSION", "CLAUDE_PID",
            "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_EXECPATH");

    /** How long {@link #close} lets the CLI exit on its own after stdin closes before killing it. */
    private static final long CLOSE_GRACE_MS = 1000;
    private static final AtomicLong SESSIONS = new AtomicLong();

    /**
     * How to start the CLI. Every field but {@code executable} is optional (null, blank or empty means "not set").
     *
     * @param executable          the CLI, usually from {@link ClaudeCli#locate}
     * @param workingDirectory    where the agent works; null for Maistro's own
     * @param model               {@code --model}, e.g. {@code sonnet} or a full model id
     * @param permissionMode      {@code --permission-mode}: {@code default}, {@code acceptEdits}, {@code plan},
     *                            {@code bypassPermissions}, ...
     * @param appendSystemPrompt  {@code --append-system-prompt}
     * @param resumeSessionId     {@code --resume}: continue an earlier session by its id
     * @param extraArgs           more arguments, appended as given (e.g. {@code --max-turns 5})
     * @param environment         variables to set (or, with a null value, remove) in the child's environment
     * @param scrubHostVariables  remove {@link #HOST_SESSION_VARIABLES} from the inherited environment
     */
    public record Options(Path executable, Path workingDirectory, String model, String permissionMode,
                          String appendSystemPrompt, String resumeSessionId, List<String> extraArgs,
                          Map<String, String> environment, boolean scrubHostVariables) {

        public Options {
            Objects.requireNonNull(executable, "executable");
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            environment = environment == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new HashMap<>(environment));
        }

        /** Options with only the executable set, and host variables scrubbed. */
        public static Options of(Path executable) {
            return new Options(executable, null, null, null, null, null, List.of(), Map.of(), true);
        }

        public Options withWorkingDirectory(Path dir) {
            return new Options(executable, dir, model, permissionMode, appendSystemPrompt, resumeSessionId,
                    extraArgs, environment, scrubHostVariables);
        }

        public Options withModel(String m) {
            return new Options(executable, workingDirectory, m, permissionMode, appendSystemPrompt, resumeSessionId,
                    extraArgs, environment, scrubHostVariables);
        }

        public Options withPermissionMode(String mode) {
            return new Options(executable, workingDirectory, model, mode, appendSystemPrompt, resumeSessionId,
                    extraArgs, environment, scrubHostVariables);
        }

        public Options withAppendSystemPrompt(String prompt) {
            return new Options(executable, workingDirectory, model, permissionMode, prompt, resumeSessionId,
                    extraArgs, environment, scrubHostVariables);
        }

        public Options withResume(String sessionId) {
            return new Options(executable, workingDirectory, model, permissionMode, appendSystemPrompt, sessionId,
                    extraArgs, environment, scrubHostVariables);
        }

        /**
         * Ask over stdout before a tool that needs approval runs ({@code --permission-prompt-tool stdio}), as
         * {@link AgentEvent.PermissionRequest}s. Without it, such a tool is refused without anyone being asked.
         */
        public Options withPermissionPrompts() {
            List<String> args = new ArrayList<>(extraArgs);
            args.addAll(List.of("--permission-prompt-tool", "stdio"));
            return withExtraArgs(args);
        }

        public Options withExtraArgs(List<String> args) {
            return new Options(executable, workingDirectory, model, permissionMode, appendSystemPrompt,
                    resumeSessionId, args, environment, scrubHostVariables);
        }

        public Options withEnvironment(Map<String, String> env) {
            return new Options(executable, workingDirectory, model, permissionMode, appendSystemPrompt,
                    resumeSessionId, extraArgs, env, scrubHostVariables);
        }

        public Options withScrubHostVariables(boolean scrub) {
            return new Options(executable, workingDirectory, model, permissionMode, appendSystemPrompt,
                    resumeSessionId, extraArgs, environment, scrub);
        }

        /** The full command line this starts. */
        public List<String> command() {
            List<String> c = new ArrayList<>();
            c.add(executable.toString());
            c.addAll(List.of("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                    "--include-partial-messages"));
            addIfSet(c, "--model", model);
            addIfSet(c, "--permission-mode", permissionMode);
            addIfSet(c, "--append-system-prompt", appendSystemPrompt);
            addIfSet(c, "--resume", resumeSessionId);
            c.addAll(extraArgs);
            return c;
        }

        /** The child's environment, built from {@code inherited} (normally {@link System#getenv()}). */
        public Map<String, String> childEnvironment(Map<String, String> inherited) {
            Map<String, String> env = new HashMap<>(inherited);
            if (scrubHostVariables) {
                env.keySet().removeIf(k -> HOST_SESSION_VARIABLES.contains(k.toUpperCase(java.util.Locale.ROOT)));
            }
            environment.forEach((k, v) -> {
                if (v == null) {
                    env.remove(k);
                } else {
                    env.put(k, v);
                }
            });
            return env;
        }

        private static void addIfSet(List<String> c, String flag, String value) {
            if (value != null && !value.isBlank()) {
                c.add(flag);
                c.add(value);
            }
        }
    }

    private final AgentProcess process;
    private final AgentListener listener;
    private final OutputStream stdin;
    private final Object writeLock = new Object();
    private final Thread reader;
    private final Thread errReader;
    private final long number;
    private final AtomicLong requests = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean exited;
    private volatile String sessionId = "";

    private AgentSession(AgentProcess process, AgentListener listener, long number) {
        this.process = process;
        this.listener = listener;
        this.stdin = process.stdin();
        this.number = number;
        this.reader = Thread.ofPlatform().daemon().name("maistro-agent-" + number + "-out").unstarted(this::readOut);
        this.errReader = Thread.ofPlatform().daemon().name("maistro-agent-" + number + "-err").unstarted(this::readErr);
    }

    /** Starts the real CLI. */
    public static AgentSession start(Options options, AgentListener listener) throws IOException {
        return start(options, listener, AgentProcess.SYSTEM);
    }

    /** Starts the CLI through {@code launcher}; tests pass one that plays back recorded output. */
    public static AgentSession start(Options options, AgentListener listener, AgentProcess.Launcher launcher)
            throws IOException {
        Objects.requireNonNull(listener, "listener");
        long n = SESSIONS.incrementAndGet();
        List<String> command = options.command();
        LOG.info("agent {}: starting {} in {}", n, describe(command),
                options.workingDirectory() == null ? "the current directory" : options.workingDirectory());
        AgentProcess p = launcher.launch(command, options.workingDirectory(),
                options.childEnvironment(System.getenv()));
        AgentSession s = new AgentSession(p, listener, n);
        s.reader.start();
        s.errReader.start();
        return s;
    }

    /** Sends a user turn of plain text. False if the session is closed or the write failed (logged). */
    public boolean send(String text) {
        return sendUser(new JsonValue.Str(text));
    }

    /**
     * Sends a user turn whose content is a string or a list of content blocks (text, image, ...), as the Messages
     * API shapes them.
     */
    public boolean sendUser(JsonValue content) {
        JsonValue message = JsonValue.Obj.builder()
                .put("type", "user")
                .put("message", JsonValue.Obj.builder().put("role", "user").put("content", content).build())
                .build();
        return writeLine(message);
    }

    /**
     * Asks the CLI to stop the turn in progress. The process keeps running and takes the next {@link #send}; the
     * CLI acknowledges with an {@link AgentEvent.ControlResponse} carrying the returned id, and the stopped turn
     * still ends with a {@link AgentEvent.Result}.
     *
     * @return the request id, or {@code ""} when nothing could be sent
     */
    public String interrupt() {
        String id = "maistro-" + number + "-" + requests.incrementAndGet();
        JsonValue req = JsonValue.Obj.builder()
                .put("type", "control_request")
                .put("request_id", id)
                .put("request", JsonValue.Obj.builder().put("subtype", "interrupt").build())
                .build();
        return writeLine(req) ? id : "";
    }

    /**
     * Let the tool of an {@link AgentEvent.PermissionRequest} run, with {@code input} as it was asked for. False if
     * nothing could be written.
     */
    public boolean allow(String requestId, JsonValue input) {
        return answer(requestId, JsonValue.Obj.builder()
                .put("behavior", "allow")
                .put("updatedInput", input.isObject() ? input : JsonValue.Obj.EMPTY)
                .build());
    }

    /** Refuse the tool of an {@link AgentEvent.PermissionRequest}; the agent is told {@code message}. */
    public boolean deny(String requestId, String message) {
        return answer(requestId, JsonValue.Obj.builder()
                .put("behavior", "deny")
                .put("message", message)
                .build());
    }

    /** Answer a {@link AgentEvent.ControlRequest} this code does not handle with an error, rather than silence. */
    public boolean refuse(String requestId, String error) {
        return writeLine(JsonValue.Obj.builder()
                .put("type", "control_response")
                .put("response", JsonValue.Obj.builder()
                        .put("subtype", "error")
                        .put("request_id", requestId)
                        .put("error", error)
                        .build())
                .build());
    }

    private boolean answer(String requestId, JsonValue response) {
        return writeLine(JsonValue.Obj.builder()
                .put("type", "control_response")
                .put("response", JsonValue.Obj.builder()
                        .put("subtype", "success")
                        .put("request_id", requestId)
                        .put("response", response)
                        .build())
                .build());
    }

    /** Writes one line of stream-json input as is. False if the session is closed or the write failed. */
    public boolean writeLine(JsonValue line) {
        if (closed || exited) {
            LOG.debug("agent {}: not sending, the session is {}", number, closed ? "closed" : "over");
            return false;
        }
        byte[] bytes = (line.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                stdin.write(bytes);
                stdin.flush();
                return true;
            } catch (IOException e) {
                LOG.warn("agent " + number + ": could not write to the CLI", e);
                return false;
            }
        }
    }

    /** The CLI's session id once {@link AgentEvent.SessionInit} has arrived (for {@code --resume}); else empty. */
    public Optional<String> sessionId() {
        return sessionId.isEmpty() ? Optional.empty() : Optional.of(sessionId);
    }

    /** Whether the process is still running (its output has not ended). */
    public boolean isRunning() {
        return !exited;
    }

    /**
     * Ends the session: closes stdin, gives the CLI a moment to exit, then kills it and everything it started.
     * The listener still gets {@link AgentEvent.ProcessExit}. Safe to call more than once and from any thread.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Not under writeLock: a write stuck on a full pipe must not keep close from ending the process.
        try {
            stdin.close();
        } catch (IOException ignored) {
            // the pipe is already gone, which is what closing wants
        }
        try {
            if (!process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                LOG.debug("agent {}: did not exit when its input closed; ending it", number);
            }
            process.destroyTree();
            if (Thread.currentThread() != reader) {
                reader.join(2000);
            }
        } catch (InterruptedException e) {
            process.destroyTree();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private void readOut() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (AgentEvent e : EventParser.parse(line)) {
                    e.to(watcher);
                    deliver(e);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("agent " + number + ": reading the CLI's output failed", e);
            }
        }
        int code = -1;
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                LOG.warn("agent {}: output ended but the process did not exit; ending it", number);
                process.destroyTree();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            code = process.exitCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.debug("agent " + number + ": no exit code", e);
        }
        exited = true;
        LOG.info("agent {}: exited with {}", number, code);
        deliver(new AgentEvent.ProcessExit(code));
    }

    /** What the session itself takes from the stream before the listener sees it: the id, and log lines. */
    private final AgentEvent.Sink watcher = new AgentEvent.Sink() {
        @Override
        public void onSessionInit(AgentEvent.SessionInit init) {
            if (!init.sessionId().isEmpty()) {
                sessionId = init.sessionId();
            }
            LOG.info("agent {}: session {} on {}", number, init.sessionId(), init.model());
        }

        @Override
        public void onResult(AgentEvent.Result r) {
            LOG.info("agent {}: turn ended, {} ({}), {} turns, {} ms", number, r.success() ? "ok" : "failed",
                    r.subtype(), r.numTurns(), r.durationMs());
        }

        @Override
        public void onUnknown(AgentEvent.Unknown u) {
            LOG.debug("agent {}: unrecognized output ({}): {}", number, u.reason(), abbreviate(u.line()));
        }
    };

    private void readErr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.stderr(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    LOG.warn("agent {} stderr: {}", number, abbreviate(line));
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.debug("agent " + number + ": reading stderr failed", e);
            }
        }
    }

    private void deliver(AgentEvent e) {
        try {
            listener.onEvent(e);
        } catch (RuntimeException ex) {
            LOG.error("agent " + number + ": listener failed on " + e.getClass().getSimpleName(), ex);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "... (" + s.length() + " chars)";
    }

    /** The command for the log, with the system prompt's text left out. */
    private static String describe(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String a = command.get(i);
            if (i > 0 && command.get(i - 1).equals("--append-system-prompt")) {
                sb.append('<').append(a.length()).append(" chars>");
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }
}
