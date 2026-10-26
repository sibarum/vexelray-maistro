package dev.vexelray.maistro.tools;

import dev.vexelray.maistro.agent.JsonValue;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Maistro's tools as the CLI is given them: an {@link McpServer}, the config file that names it, and the threads its
 * calls run on.
 *
 * <p><b>Threads.</b> A message arrives on a CLI's reader thread, which must not wait on a tool: a {@code shot} can
 * take seconds, and the reader also carries the interrupt's answer. So {@link #handle} gives each message a virtual
 * thread of its own and returns at once; the reply is handed to the caller's {@code reply} on that thread. Nothing
 * here touches the model.
 */
public final class Toolset implements AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.tools");

    private final McpServer server;
    private final Path configFile;
    private final List<String> allowed;
    private final ExecutorService calls = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("maistro-tool-", 0).factory());
    private boolean written;

    /**
     * @param server     the tools
     * @param configFile where the CLI's {@code --mcp-config} is written, once, when first wanted
     * @param allowed    the tools that run without asking the human, by their short names
     */
    public Toolset(McpServer server, Path configFile, List<String> allowed) {
        this.server = server;
        this.configFile = configFile;
        this.allowed = allowed.stream().map(server::qualified).toList();
    }

    /** The server's name, which the CLI puts on each of its messages. */
    public String name() {
        return server.name();
    }

    /** The tools that run without asking, by the names the agent knows them by. */
    public List<String> allowed() {
        return allowed;
    }

    /** The config file for {@code --mcp-config}, written the first time it is asked for. Disk work: not on the frame loop. */
    public synchronized Path config() throws IOException {
        if (!written) {
            Files.createDirectories(configFile.toAbsolutePath().getParent());
            Files.writeString(configFile, server.config().toJson(), StandardCharsets.UTF_8);
            written = true;
        }
        return configFile;
    }

    /** Answers {@code message} on a tool thread, and gives the reply to {@code reply} there. Returns at once. */
    public void handle(JsonValue message, Consumer<JsonValue> reply) {
        try {
            calls.execute(() -> {
                JsonValue answer = server.answer(message);
                LOG.debug("{} {}", message.get("method").asString(), message.get("params").get("name").asString());
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
