import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A stand-in for {@code claude.exe} that a person or an agent plays by hand: Maistro talks to it as it talks to the
 * real CLI, and whoever holds the other end answers as the model. The way to try a scenario (a tool call, a permission
 * prompt, a map block, an interrupt) at once, without a login, a network or a model deciding to do it.
 *
 * <p>Two modes in one file, run as a single source file with Maistro's own classes on the class path (for its JSON):
 * <ul>
 *   <li>{@code serve <dir>}: the stand-in, started by Maistro through {@code relay-claude.cmd}. It reads stream-json on
 *       stdin and writes it on stdout as the CLI does, and listens on a loopback port it writes to
 *       {@code <dir>/relay-<pid>.port}. It records every line it is sent in {@code <dir>/stdin-<pid>.txt}.</li>
 *   <li>{@code client <dir> <verb> [args]}: one command to the newest live stand-in, run by {@code relay} or
 *       {@code relay.cmd}. A command that carries text or JSON (say, text, think, tool, ask) reads it from stdin.</li>
 * </ul>
 *
 * <p>Like the real CLI, the stand-in says nothing until the first message arrives. Then it does the MCP handshake
 * with Maistro's in-process server over the control channel, prints {@code system/init} with the tools it was told
 * about, and holds the message for {@code next}. A turn stays open until {@code say} or {@code end} closes it.
 */
public class RelayClaude {

    static final String HELP = """
            relay next [seconds]   wait for what Maistro sends next (a message, or an interrupt) and print it
            relay say              (stdin) the reply's text, and end the turn
            relay text             (stdin) text, turn left open
            relay think            (stdin) a thinking block
            relay tool <name>      (stdin: JSON arguments, may be empty) call Maistro's tool <name> as the CLI does;
                                   prints the result, and saves any picture under the relay folder
            relay ask <Tool>       (stdin: JSON input) ask permission to run a tool, wait for the human's answer
            relay end [error]      end the turn (as a failure with 'error')
            relay status           the session, the open turn, the tools, what is waiting
            relay exit [code]      the stand-in exits, as a CLI that crashed (default 1)
            relay list             the stand-ins running""";

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("serve")) {
            new Server(Path.of(args[1])).run();
        } else if (args.length >= 3 && args[0].equals("client")) {
            System.exit(Client.run(Path.of(args[1]), List.of(args).subList(2, args.length)));
        } else {
            System.err.println("usage: RelayClaude serve <dir> | client <dir> <verb> [args]\n" + HELP);
            System.exit(2);
        }
    }

    // ================================================================================================================
    // The stand-in

    static final class Server {
        /** How long the handshake waits on each answer before the server is reported as failed. */
        static final long MCP_WAIT_S = 10;
        /** How long a tool call waits for Maistro's answer. */
        static final long TOOL_WAIT_S = 120;

        final Path dir;
        final long pid = ProcessHandle.current().pid();
        final String sessionId = UUID.randomUUID().toString();
        final PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        final Map<String, CompletableFuture<JsonValue>> waiting = new ConcurrentHashMap<>();
        final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        final List<String> mcpTools = new ArrayList<>();
        final String server = System.getenv().getOrDefault("RELAY_MCP_SERVER", "maistro");
        volatile boolean started;
        volatile String mcpStatus = "not asked";
        volatile boolean turnOpen;
        volatile int turns;
        int mcpId;

        Server(Path dir) {
            this.dir = dir;
        }

        void run() throws Exception {
            Files.createDirectories(dir);
            ServerSocket socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Path portFile = dir.resolve("relay-" + pid + ".port");
            Files.writeString(portFile, Integer.toString(socket.getLocalPort()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(portFile);
                } catch (IOException ignored) {
                    // a stale file is cleared by the next client
                }
            }));
            Thread commands = Thread.ofPlatform().daemon().name("relay-commands").start(() -> serve(socket));
            readStdin();
            commands.interrupt();
            socket.close();
        }

        // ---- what Maistro sends

        void readStdin() throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                record(line);
                JsonValue v;
                try {
                    v = Json.parse(line);
                } catch (RuntimeException e) {
                    continue;
                }
                switch (v.get("type").asString()) {
                    case "user" -> heard(v);
                    case "control_response" -> answered(v.get("response"));
                    case "control_request" -> requested(v);
                    default -> { }
                }
            }
        }

        void heard(JsonValue v) {
            String told = describe(v.get("message").get("content"));
            if (!started) {
                started = true;
                // Off the reader: the handshake waits for answers that arrive on stdin.
                Thread.ofPlatform().daemon().name("relay-start").start(() -> {
                    handshake();
                    emit(init());
                    inbox.add("message\n" + told);
                });
            } else {
                inbox.add("message\n" + told);
            }
        }

        void answered(JsonValue response) {
            CompletableFuture<JsonValue> f = waiting.remove(response.get("request_id").asString());
            if (f != null) {
                f.complete(response);
            }
        }

        void requested(JsonValue v) {
            String subtype = v.get("request").get("subtype").asString();
            emit(JsonValue.Obj.builder().put("type", "control_response").put("response", JsonValue.Obj.builder()
                    .put("subtype", "success").put("request_id", v.get("request_id").asString())
                    .put("response", JsonValue.Obj.EMPTY).build()).build());
            if (subtype.equals("interrupt")) {
                if (turnOpen) {
                    endTurn(true, "error_during_execution", "interrupted");
                }
                inbox.add("interrupt");
            }
        }

        // ---- the MCP handshake, as claude.exe 2.1.308 was recorded doing it

        void handshake() {
            try {
                mcp("initialize", JsonValue.Obj.builder().put("protocolVersion", "2025-11-25")
                        .put("capabilities", JsonValue.Obj.EMPTY)
                        .put("clientInfo", JsonValue.Obj.builder().put("name", "relay-claude").put("version", "0")
                                .build()).build(), true);
                mcp("notifications/initialized", null, false);
                JsonValue list = mcp("tools/list", null, true);
                for (JsonValue t : list.get("result").get("tools").items()) {
                    mcpTools.add(t.get("name").asString());
                }
                mcpStatus = "connected";
            } catch (TimeoutException e) {
                mcpStatus = "failed (no answer: is Maistro running without its tools?)";
            } catch (Exception e) {
                mcpStatus = "failed (" + e + ")";
            }
        }

        /** One JSON-RPC message to Maistro's server; its reply (the {@code mcp_response}). */
        JsonValue mcp(String method, JsonValue params, boolean withId) throws Exception {
            JsonValue.Obj.Builder m = JsonValue.Obj.builder().put("jsonrpc", "2.0");
            if (withId) {
                m.put("id", mcpId++);
            }
            m.put("method", method);
            if (params != null) {
                m.put("params", params);
            }
            JsonValue r = request(JsonValue.Obj.builder().put("subtype", "mcp_message").put("server_name", server)
                    .put("message", m.build()).build(), method.equals("tools/call") ? TOOL_WAIT_S : MCP_WAIT_S);
            return r.get("response").get("mcp_response");
        }

        /** A control_request to Maistro, and its answer. */
        JsonValue request(JsonValue body, long seconds) throws Exception {
            String id = "relay-" + ids.incrementAndGet();
            CompletableFuture<JsonValue> f = new CompletableFuture<>();
            waiting.put(id, f);
            emit(JsonValue.Obj.builder().put("type", "control_request").put("request_id", id).put("request", body)
                    .build());
            try {
                return f.get(seconds, TimeUnit.SECONDS);
            } finally {
                waiting.remove(id);
            }
        }

        // ---- the commands

        void serve(ServerSocket socket) {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    InputStream in = s.getInputStream();
                    String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    int nl = all.indexOf('\n');
                    String command = nl < 0 ? all : all.substring(0, nl);
                    String payload = nl < 0 ? "" : all.substring(nl + 1);
                    String reply;
                    try {
                        reply = command(command.strip(), payload);
                    } catch (Exception e) {
                        reply = "err " + e;
                    }
                    OutputStream o = s.getOutputStream();
                    o.write((reply.endsWith("\n") ? reply : reply + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (IOException e) {
                    if (socket.isClosed()) {
                        return;
                    }
                }
            }
        }

        String command(String command, String payload) throws Exception {
            String[] w = command.split("\\s+", 2);
            String verb = w[0];
            String arg = w.length > 1 ? w[1].strip() : "";
            return switch (verb) {
                case "next" -> next(arg.isEmpty() ? 120 : Long.parseLong(arg));
                case "say" -> {
                    String said = text(payload);
                    endTurn(false, "success", said);
                    yield "ok said, turn ended";
                }
                case "text" -> text(payload).isEmpty() ? "ok" : "ok text";
                case "think" -> {
                    open();
                    emit(assistant(JsonValue.Obj.builder().put("type", "thinking").put("thinking", payload.strip())
                            .put("signature", "").build()));
                    yield "ok thought";
                }
                case "tool" -> tool(arg, payload);
                case "ask" -> ask(arg, payload);
                case "end" -> {
                    endTurn(!arg.isEmpty(), arg.isEmpty() ? "success" : "error_during_execution",
                            arg.isEmpty() ? "ok" : arg);
                    yield "ok turn ended";
                }
                case "status" -> "ok pid=" + pid + " session=" + sessionId + " started=" + started
                        + " turn=" + (turnOpen ? "open" : "closed") + " turns=" + turns + " waiting=" + inbox.size()
                        + "\nmcp " + server + ": " + mcpStatus + " " + mcpTools;
                case "exit" -> {
                    int code = arg.isEmpty() ? 1 : Integer.parseInt(arg);
                    Thread.ofPlatform().start(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                            // exiting anyway
                        }
                        Runtime.getRuntime().halt(code);
                    });
                    yield "ok exiting with " + code;
                }
                case "help" -> "ok\n" + HELP;
                default -> "err no command '" + verb + "'; try help";
            };
        }

        String next(long seconds) throws InterruptedException {
            String got = inbox.poll(seconds, TimeUnit.SECONDS);
            if (got == null) {
                return "err nothing arrived within " + seconds + "s";
            }
            if (got.startsWith("message\n")) {
                open();
                return "ok message (turn " + turns + " open)\n" + got.substring("message\n".length());
            }
            return "ok " + got;
        }

        /** Emits {@code payload} as an assistant text block, if there is any; returns it. */
        String text(String payload) {
            String t = payload.strip();
            if (!t.isEmpty()) {
                open();
                emit(assistant(JsonValue.Obj.builder().put("type", "text").put("text", t).build()));
            }
            return t;
        }

        String tool(String name, String payload) throws Exception {
            if (name.isEmpty()) {
                return "err which tool? " + mcpTools;
            }
            JsonValue args = payload.isBlank() ? JsonValue.Obj.EMPTY : Json.parse(payload);
            open();
            String useId = "toolu_relay_" + ids.incrementAndGet();
            emit(assistant(JsonValue.Obj.builder().put("type", "tool_use").put("id", useId)
                    .put("name", "mcp__" + server + "__" + name).put("input", args).build()));
            JsonValue r = mcp("tools/call", JsonValue.Obj.builder().put("name", name).put("arguments", args).build(),
                    true);
            JsonValue result = r.get("result");
            boolean isError = result.get("isError").asBool(false) || r.has("error");
            StringBuilder shown = new StringBuilder(isError ? "ok tool answered with an error\n" : "ok\n");
            List<JsonValue> back = new ArrayList<>();
            int n = 0;
            for (JsonValue block : result.get("content").items()) {
                if (block.get("type").asString().equals("image")) {
                    Path png = dir.resolve("images").resolve(useId + "-" + (n++) + ".png");
                    Files.createDirectories(png.getParent());
                    Files.write(png, Base64.getDecoder().decode(block.get("data").asString()));
                    shown.append("[image saved to ").append(png.toAbsolutePath()).append("]\n");
                } else {
                    shown.append(block.get("text").asString()).append('\n');
                }
                back.add(block);
            }
            if (r.has("error")) {
                shown.append(r.get("error").toJson()).append('\n');
                back.add(JsonValue.Obj.builder().put("type", "text").put("text", r.get("error").toJson()).build());
            }
            emit(toolResult(useId, new JsonValue.Arr(back), isError));
            return shown.toString();
        }

        String ask(String tool, String payload) throws Exception {
            if (tool.isEmpty()) {
                return "err which tool? e.g. relay ask Bash, with {\"command\": \"...\"} on stdin";
            }
            JsonValue input = payload.isBlank() ? JsonValue.Obj.EMPTY : Json.parse(payload);
            open();
            String useId = "toolu_relay_" + ids.incrementAndGet();
            emit(assistant(JsonValue.Obj.builder().put("type", "tool_use").put("id", useId).put("name", tool)
                    .put("input", input).build()));
            JsonValue answer = request(JsonValue.Obj.builder().put("subtype", "can_use_tool").put("tool_name", tool)
                    .put("input", input).put("tool_use_id", useId)
                    .put("permission_suggestions", JsonValue.Arr.of(JsonValue.Obj.builder()
                            .put("type", "addRules").put("behavior", "allow").put("destination", "session")
                            .put("rules", JsonValue.Arr.of(JsonValue.Obj.builder().put("toolName", tool).build()))
                            .build()))
                    .build(), 3600);
            JsonValue decision = answer.get("response");
            boolean allowed = decision.get("behavior").asString().equals("allow");
            String said = allowed ? "(the relay ran nothing)" : decision.get("message").asString();
            emit(toolResult(useId, new JsonValue.Str(said), !allowed));
            return "ok " + (allowed ? "allowed" : "denied") + "\n" + decision.toJson();
        }

        // ---- what the stand-in writes

        void open() {
            turnOpen = true;
        }

        void endTurn(boolean error, String subtype, String result) {
            turnOpen = false;
            turns++;
            emit(JsonValue.Obj.builder().put("type", "result").put("subtype", subtype).put("is_error", error)
                    .put("duration_ms", 1).put("duration_api_ms", 1).put("num_turns", 1).put("result", result)
                    .put("stop_reason", "end_turn").put("session_id", sessionId).put("total_cost_usd", 0)
                    .put("usage", JsonValue.Obj.builder().put("input_tokens", 0).put("output_tokens", 0).build())
                    .put("permission_denials", JsonValue.Arr.EMPTY).put("uuid", UUID.randomUUID().toString())
                    .build());
        }

        JsonValue init() {
            List<String> tools = new ArrayList<>(List.of("Bash", "Read", "Edit", "Write", "Glob", "Grep"));
            for (String t : mcpTools) {
                tools.add("mcp__" + server + "__" + t);
            }
            return JsonValue.Obj.builder().put("type", "system").put("subtype", "init")
                    .put("cwd", Path.of("").toAbsolutePath().toString()).put("session_id", sessionId)
                    .put("tools", JsonValue.Arr.ofStrings(tools))
                    .put("mcp_servers", JsonValue.Arr.of(JsonValue.Obj.builder().put("name", server)
                            .put("status", mcpStatus.startsWith("connected") ? "connected" : "failed")
                            .put("source", "sdk").build()))
                    .put("model", "relay").put("permissionMode", "default").put("apiKeySource", "none")
                    .put("claude_code_version", "relay").put("uuid", UUID.randomUUID().toString()).build();
        }

        JsonValue assistant(JsonValue block) {
            return JsonValue.Obj.builder().put("type", "assistant").put("message", JsonValue.Obj.builder()
                    .put("model", "relay").put("id", "msg_relay_" + ids.incrementAndGet()).put("type", "message")
                    .put("role", "assistant").put("content", JsonValue.Arr.of(block)).build())
                    .put("parent_tool_use_id", JsonValue.Null.NULL).put("session_id", sessionId)
                    .put("uuid", UUID.randomUUID().toString()).build();
        }

        JsonValue toolResult(String useId, JsonValue content, boolean isError) {
            return JsonValue.Obj.builder().put("type", "user").put("message", JsonValue.Obj.builder()
                    .put("role", "user").put("content", JsonValue.Arr.of(JsonValue.Obj.builder()
                            .put("type", "tool_result").put("tool_use_id", useId).put("content", content)
                            .put("is_error", isError).build())).build())
                    .put("parent_tool_use_id", JsonValue.Null.NULL).put("session_id", sessionId)
                    .put("uuid", UUID.randomUUID().toString()).build();
        }

        synchronized void emit(JsonValue line) {
            out.println(line.toJson());
        }

        void record(String line) {
            try {
                Files.writeString(dir.resolve("stdin-" + pid + ".txt"), line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // the record is a convenience; the relay works without it
            }
        }

        /** A user turn's content as text: the string, or its text blocks, with other blocks named. */
        static String describe(JsonValue content) {
            if (content.isString()) {
                return content.asString();
            }
            StringBuilder sb = new StringBuilder();
            for (JsonValue b : content.items()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                String type = b.get("type").asString();
                sb.append(type.equals("text") ? b.get("text").asString() : "[" + type + " block]");
            }
            return sb.toString();
        }
    }

    // ================================================================================================================
    // The client

    static final class Client {
        static int run(Path dir, List<String> words) throws IOException {
            String verb = words.getFirst();
            if (verb.equals("list")) {
                List<Live> live = live(dir);
                System.out.println("ok " + live.size());
                for (Live l : live) {
                    System.out.println(l.pid() + " port " + l.port());
                }
                return 0;
            }
            String want = System.getenv("RELAY_PID");
            // next waits for a stand-in as well as for what it hears: Maistro starts one only on the first message,
            // and compiling it from source takes a few seconds.
            long deadline = System.nanoTime() + (verb.equals("next") ? seconds(words) : 0) * 1_000_000_000L;
            Live target = pick(live(dir), want);
            while (target == null && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                target = pick(live(dir), want);
            }
            if (target == null) {
                System.err.println("relay: no stand-in is running"
                        + (want == null ? "" : " with pid " + want) + " under " + dir.toAbsolutePath()
                        + "; start Maistro with --claude=src/test/relay/relay-claude.cmd and send it a message");
                return 3;
            }
            String payload = takesPayload(verb) ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8) : "";
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), target.port())) {
                s.getOutputStream().write((String.join(" ", words) + "\n" + payload).getBytes(StandardCharsets.UTF_8));
                s.shutdownOutput();
                String reply = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                System.out.print(reply);
                return reply.startsWith("err") ? 1 : 0;
            }
        }

        /** The newest live stand-in, or the one whose pid is {@code want}; null if there is none. */
        static Live pick(List<Live> live, String want) {
            Live target = null;
            for (Live l : live) {
                if (want == null || want.isBlank() || Long.toString(l.pid()).equals(want.strip())) {
                    target = l;
                }
            }
            return target;
        }

        /** {@code next}'s timeout in seconds, as the server reads it. */
        static long seconds(List<String> words) {
            return words.size() > 1 ? Long.parseLong(words.get(1)) : 120;
        }

        static boolean takesPayload(String verb) {
            return List.of("say", "text", "think", "tool", "ask").contains(verb);
        }

        record Live(long pid, int port, long modified) {
        }

        /** The stand-ins whose process is still alive, oldest first; stale port files are deleted. */
        static List<Live> live(Path dir) throws IOException {
            List<Live> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(f -> f.getFileName().toString().matches("relay-\\d+\\.port")).toList()) {
                    String name = p.getFileName().toString();
                    long pid = Long.parseLong(name.substring(6, name.length() - 5));
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                        out.add(new Live(pid, Integer.parseInt(Files.readString(p).strip()),
                                Files.getLastModifiedTime(p).toMillis()));
                    } else {
                        Files.deleteIfExists(p);
                    }
                }
            }
            out.sort(Comparator.comparingLong(Live::modified));
            return out;
        }
    }
}
