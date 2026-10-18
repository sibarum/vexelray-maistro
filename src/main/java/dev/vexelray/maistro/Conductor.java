package dev.vexelray.maistro;

import dev.vexelray.maistro.agent.AgentEvent;
import dev.vexelray.maistro.agent.AgentListener;
import dev.vexelray.maistro.agent.AgentSession;
import dev.vexelray.maistro.agent.ClaudeCli;
import dev.vexelray.maistro.agent.JsonValue;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.MapDelta;
import dev.vexelray.maistro.map.MapGraph;
import dev.vexelray.maistro.map.MapProtocol;
import dev.vexelray.maistro.map.Node;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The agent's side of the conversation: one Claude Code process, and what turns its events into changes to the
 * session.
 *
 * <h2>Threads</h2>
 *
 * <p>{@link #send} is called on a worker. Starting the process is I/O, so the first send starts it right there, on
 * the worker that is already off the frame loop, and later sends write one line to its stdin. Events arrive on the
 * session's reader thread, one at a time and in order ({@link AgentListener}), and each becomes one commit to the
 * {@link Model}; nothing here holds a lock while committing, and nothing here is reached from a model listener.
 *
 * <h2>One process, resumed when it goes</h2>
 *
 * <p>The process lives for the conversation. If it exits (a crash, a kill, an auth failure), the next send starts a
 * new one with {@code --resume} and the session id the last one reported, so the conversation carries on.
 */
final class Conductor implements AgentListener, AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.agent");

    /**
     * What a launch needs, from settings: an empty string is "not set".
     *
     * @param claude         the CLI's path; empty looks on {@code PATH}, then in the Claude desktop app's copy
     * @param model          {@code --model}
     * @param permissionMode {@code --permission-mode}
     * @param cwd            where the agent works; empty for Maistro's own working directory
     */
    record Launch(String claude, String model, String permissionMode, String cwd) {
    }

    /** Appended to Claude Code's system prompt: where it is running, and what is around it. */
    static final String CONTEXT = """
            You are running inside Maistro, a desktop client for Claude Code built on the VexelRay stack. The human \
            reads your replies in a conversation pane beside a mind map of the conversation, so prefer short \
            sections with clear headings over long walls of text. The VexelRay desktop applications can be driven \
            with ottermate (C:/Users/User/Documents/GitHub/vexelray-gui/vexelray-gui-automation-cli/ottermate, \
            guide at vexelray-gui/docs/guides/ottermate.md): keys, clicks, reading the tree and readouts, \
            screenshots. Use it when asked to operate one of them.""";

    private final Model model;
    private final Launch launch;
    /** Tool-use id to the transcript turn showing it. */
    private final Map<String, Long> tools = new ConcurrentHashMap<>();

    private AgentSession session;
    private String lastSessionId = "";

    Conductor(Model model, Launch launch) {
        this.model = model;
        this.launch = launch;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Say {@code text} to the agent. On a worker; starts the process the first time.
     *
     * <p>If the human picked a node, the message starts from it: the agent is told so and given the node's
     * neighbourhood ({@link #fromNode}), the transcript shows the text with a {@code from · <label>} chip, and the
     * anchor is let go. The anchor is read inside the same commit that lets it go, so a click landing meanwhile is
     * either in this message or kept for the next, never lost. A commit's function may run more than once, and the
     * last run is the one committed, so the prompt it leaves is the one that matches the transcript.
     */
    void send(String text) {
        AtomicReference<String> prompt = new AtomicReference<>(text);
        model.change(d -> {
            Optional<Node> from = d.mind().anchorNode();
            prompt.set(from.map(n -> fromNode(d.mind().graph(), n, text)).orElse(text));
            List<String> chips = from.map(n -> List.of("from · " + n.shownLabel())).orElse(List.of());
            return d.withTranscript(t -> t.append(id -> Turn.human(id, text).withChips(chips)))
                    .withMind(Doc.Mind::spoke)
                    .withActivity("thinking", true);
        });
        AgentSession s = sessionOrStart();
        if (s == null) {
            model.change(d -> d.withActivity("not started", false));
            return;
        }
        if (!s.send(prompt.get())) {
            failed("Could not write to Claude Code: its process has gone.");
        }
    }

    /** Stop the turn in progress. The process stays up for the next message. */
    void interrupt() {
        AgentSession s = current();
        if (s != null && !s.interrupt().isEmpty()) {
            model.say("Interrupted");
        }
    }

    private synchronized AgentSession current() {
        return session;
    }

    private synchronized AgentSession sessionOrStart() {
        if (session != null && session.isRunning()) {
            return session;
        }
        ClaudeCli.Result found = ClaudeCli.locate(launch.claude());
        if (!found.isFound()) {
            failed("Claude Code was not found: " + found.reason()
                    + ". Pass --claude=<path to claude.exe> to say where it is.");
            return null;
        }
        Path exe = found.location().orElseThrow().executable();
        AgentSession.Options options = AgentSession.Options.of(exe)
                .withAppendSystemPrompt(CONTEXT + "\n\n" + MapProtocol.INSTRUCTIONS)
                .withModel(blankToNull(launch.model()))
                .withPermissionMode(blankToNull(launch.permissionMode()))
                .withWorkingDirectory(launch.cwd().isBlank() ? null : Path.of(launch.cwd()))
                .withResume(blankToNull(lastSessionId));
        try {
            session = AgentSession.start(options, this);
            LOG.info("started Claude Code from {} ({})", exe, found.reason());
            return session;
        } catch (IOException e) {
            LOG.warn("could not start {}", exe, e);
            failed("Could not start Claude Code: " + e.getMessage());
            return null;
        }
    }

    private synchronized void remember(String sessionId) {
        if (!sessionId.isEmpty()) {
            lastSessionId = sessionId;
        }
    }

    @Override
    public void close() {
        AgentSession s;
        synchronized (this) {
            s = session;
            session = null;
        }
        if (s != null) {
            s.close();
        }
    }

    // ------------------------------------------------------------------ events, on the reader thread

    @Override
    public void onSessionInit(AgentEvent.SessionInit e) {
        remember(e.sessionId());
        model.change(d -> d.withAgent(a -> a.started(e.sessionId(), e.model(), e.cwd())));
    }

    @Override
    public void onThinkingDelta(AgentEvent.ThinkingDelta e) {
        if (e.parentToolUseId().isEmpty() && !"thinking".equals(model.doc().activity())) {
            model.change(d -> d.withActivity("thinking", true));
        }
    }

    /** A piece of the reply being written: grow the live reply, or start one. */
    @Override
    public void onTextDelta(AgentEvent.TextDelta e) {
        if (!e.parentToolUseId().isEmpty()) {
            return;
        }
        model.change(d -> {
            Turn live = d.transcript().liveReply();
            Doc next = live == null
                    ? d.withTranscript(t -> t.append(id -> Turn.agent(id, e.text(), true)))
                    : d.withTranscript(t -> t.change(live.id(), r -> r.withText(r.text() + e.text())));
            return next.withActivity("writing", true);
        });
    }

    /**
     * A whole text block of the reply. With partial messages on, the deltas already built it, so this is the
     * authoritative copy and ends the live reply; without them, it is the reply.
     *
     * <p>This is where the map grows: the block's {@code maistro-map} statements are applied to the graph, the
     * transcript keeps the prose, and the reply wears a chip saying what it added.
     */
    @Override
    public void onAssistantText(AgentEvent.AssistantText e) {
        if (!e.parentToolUseId().isEmpty()) {
            return;
        }
        MapProtocol.Parsed parsed = MapProtocol.parse(e.text());
        for (MapDelta.Problem p : parsed.delta().problems()) {
            LOG.debug("map block, line {}: {} ({})", p.line(), p.text(), p.reason());
        }
        model.change(d -> {
            MapGraph.Applied applied = d.mind().graph().applyReport(parsed.delta(), d.mind().turn());
            List<String> chips = chips(applied);
            Turn live = d.transcript().liveReply();
            Doc next = live == null
                    ? d.withTranscript(t -> t.append(id -> Turn.agent(id, parsed.prose(), false).withChips(chips)))
                    : d.withTranscript(t -> t.change(live.id(),
                            r -> r.withText(parsed.prose()).withChips(chips).finished(false)));
            return parsed.delta().isEmpty() ? next : next.withMind(m -> m.with(applied.graph()));
        });
    }

    /** What a reply did to the map, as chips: {@code map · +3 nodes}, {@code map · focus kv-cache}. */
    static List<String> chips(MapGraph.Applied applied) {
        List<String> chips = new ArrayList<>();
        int nodes = applied.addedNodes().size();
        if (nodes > 0 || applied.addedEdges() > 0) {
            chips.add("map · +" + nodes + (nodes == 1 ? " node" : " nodes")
                    + (applied.addedEdges() > 0 ? ", +" + applied.addedEdges() + " links" : ""));
        }
        applied.graph().focus().ifPresent(f -> chips.add("focus · " + f));
        return chips;
    }

    @Override
    public void onToolUse(AgentEvent.ToolUse e) {
        if (!e.parentToolUseId().isEmpty()) {
            return;
        }
        String detail = describe(e.name(), e.input());
        model.change(d -> {
            Turn live = d.transcript().liveReply();
            Doc settled = live == null ? d : d.withTranscript(t -> t.change(live.id(), r -> r.finished(false)));
            Doc next = settled.withTranscript(t -> t.append(id -> Turn.tool(id, e.name(), detail)));
            tools.put(e.id(), next.transcript().last().id());
            return next.withActivity("running " + e.name(), true);
        });
    }

    @Override
    public void onToolResult(AgentEvent.ToolResult e) {
        Long turn = tools.remove(e.toolUseId());
        if (turn == null) {
            return;
        }
        String preview = firstLine(e.text());
        model.change(d -> d.withTranscript(t -> t.change(turn, r -> {
            String text = preview.isEmpty() ? r.text() : r.text() + "\n→ " + preview;
            return r.withText(text).finished(e.isError());
        })).withActivity("thinking", true));
    }

    @Override
    public void onApiError(AgentEvent.ApiError e) {
        String what = e.text().isEmpty() ? e.error() : e.text();
        model.change(d -> d.withTranscript(t -> t.append(id -> Turn.note(id, what, true))));
    }

    /** The end of a turn: everything still growing stops, and the totals move on. */
    @Override
    public void onResult(AgentEvent.Result e) {
        remember(e.sessionId());
        tools.clear();
        model.change(d -> {
            Doc next = d.withTranscript(t -> t.settle())
                    .withActivity("idle", false)
                    .withAgent(a -> a.plus(e.costUsd(), e.numTurns(), e.usage().inputTokens(),
                            e.usage().outputTokens()));
            String why = e.resultText().isEmpty() ? e.subtype() : e.resultText();
            Turn last = d.transcript().last();
            boolean alreadySaid = last != null && last.who() == Turn.Who.NOTE && last.text().equals(why);
            if (!e.success() && !alreadySaid) {
                next = next.withTranscript(t -> t.append(id -> Turn.note(id, "The turn failed: " + why, true)));
            }
            return next;
        });
    }

    @Override
    public void onUnknown(AgentEvent.Unknown e) {
        LOG.debug("unrecognised line from Claude Code ({}): {}", e.reason(), e.line());
    }

    @Override
    public void onProcessExit(AgentEvent.ProcessExit e) {
        tools.clear();
        synchronized (this) {
            session = null;
        }
        String note = e.exitCode() == 0
                ? "Claude Code's process ended. The next message starts it again and resumes the conversation."
                : "Claude Code's process ended with exit code " + e.exitCode()
                        + ". The next message starts it again and resumes the conversation.";
        model.change(d -> d.withTranscript(t -> t.settle().append(id -> Turn.note(id, note, e.exitCode() != 0)))
                .withActivity("stopped", false));
    }

    // ------------------------------------------------------------------ helpers

    /** How far from the picked node its neighbourhood reaches: the node and what it links to directly. */
    static final int NEIGHBOURHOOD = 1;

    /**
     * What the agent is told when a message starts from {@code node}: which node, its neighbourhood as a
     * {@code maistro-map} block, and then the message. The whole conversation is still the agent's; this says where
     * to look.
     */
    static String fromNode(MapGraph graph, Node node, String text) {
        return "This message starts from the map node \"" + node.shownLabel() + "\" (" + node.id() + "). "
                + "Answer from that part of the map. Its neighbourhood:\n\n"
                + MapProtocol.render(graph.neighbourhood(node.id(), NEIGHBOURHOOD))
                + "\n" + text;
    }

    private void failed(String message) {
        model.change(d -> d.withTranscript(t -> t.append(id -> Turn.note(id, message, true)))
                .withActivity("not started", false)
                .withStatus(message));
    }

    /**
     * A tool call in one line: the argument that says what it does (a command, a path, a pattern), or the input as
     * JSON when none of those is there.
     */
    static String describe(String tool, JsonValue input) {
        for (String key : new String[] {"command", "file_path", "path", "pattern", "url", "query", "description"}) {
            String v = input.get(key).asString("");
            if (!v.isEmpty()) {
                return firstLine(v);
            }
        }
        String json = input.toJson();
        return json.length() > 200 ? json.substring(0, 200) + "…" : json;
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl) + " …";
        return line.length() > 240 ? line.substring(0, 240) + "…" : line;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
