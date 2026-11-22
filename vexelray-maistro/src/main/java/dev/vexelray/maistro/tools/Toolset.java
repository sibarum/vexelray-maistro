package dev.vexelray.maistro.tools;

import dev.vexelray.maistro.agent.JsonValue;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Maistro's tools as the CLI is given them: several {@link McpServer}s, the one config file that names them all, and
 * the threads their calls run on.
 *
 * <p><b>Several servers.</b> Each toolset is a server of its own ({@code maistro}, {@code maistro-fs}, ...), so the
 * agent sees {@code mcp__maistro-fs__read} and the human can grant a whole toolset at once. The CLI says which server
 * each message is for, and {@link #handle} gives it to that one.
 *
 * <p><b>Threads.</b> A message arrives on a CLI's reader thread, which must not wait on a tool: a {@code shot} can
 * take seconds, and the reader also carries the interrupt's answer. So {@link #handle} gives each message a virtual
 * thread of its own and returns at once; the reply is handed to the caller's {@code reply} on that thread. Nothing
 * here touches the model.
 */
public final class Toolset implements AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.tools");

    private final Map<String, McpServer> servers = new LinkedHashMap<>();
    private final Path configFile;
    private final List<String> allowed;
    private final ExecutorService calls = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("maistro-tool-", 0).factory());
    private boolean written;

    /**
     * @param servers    the toolsets, each under its own name, which must differ
     * @param configFile where the CLI's {@code --mcp-config} is written, once, when first wanted
     */
    public Toolset(List<McpServer> servers, Path configFile) {
        List<String> readOnly = new ArrayList<>();
        for (McpServer s : servers) {
            if (this.servers.putIfAbsent(s.name(), s) != null) {
                throw new IllegalArgumentException("two MCP servers are named " + s.name());
            }
            readOnly.addAll(s.readOnlyNames());
        }
        this.configFile = configFile;
        this.allowed = List.copyOf(readOnly);
    }

    /** Whether a server of this name is here, as the CLI names it on each message. */
    public boolean serves(String server) {
        return servers.containsKey(server);
    }

    /** The tools that run without asking, by the names the agent knows them by: every tool that only reads. */
    public List<String> allowed() {
        return allowed;
    }

    /** The CLI's {@code --mcp-config}: every server, each in this process. */
    public JsonValue configJson() {
        JsonValue.Obj.Builder entries = JsonValue.Obj.builder();
        for (McpServer s : servers.values()) {
            entries.put(s.name(), s.configEntry());
        }
        return JsonValue.Obj.builder().put("mcpServers", entries.build()).build();
    }

    /** The config file for {@code --mcp-config}, written the first time it is asked for. Disk work: not on the frame loop. */
    public synchronized Path config() throws IOException {
        if (!written) {
            Files.createDirectories(configFile.toAbsolutePath().getParent());
            Files.writeString(configFile, configJson().toJson(), StandardCharsets.UTF_8);
            written = true;
        }
        return configFile;
    }

    /**
     * Answers {@code message} for {@code server} on a tool thread, and gives the reply to {@code reply} there. Returns
     * at once. A server that is not here is the caller's to refuse ({@link #serves}).
     */
    public void handle(String server, JsonValue message, Consumer<JsonValue> reply) {
        McpServer s = servers.get(server);
        if (s == null) {
            throw new IllegalArgumentException("no MCP server " + server + "; ask serves() first");
        }
        try {
            calls.execute(() -> {
                JsonValue answer = s.answer(message);
                LOG.debug("{} {} {}", server, message.get("method").asString(),
                        message.get("params").get("name").asString());
                reply.accept(answer);
            });
        } catch (RejectedExecutionException e) {
            LOG.debug("not answering {}: the tools are closed", message.get("method").asString());
        }
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }
}
