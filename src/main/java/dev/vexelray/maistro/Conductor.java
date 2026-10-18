package dev.vexelray.maistro;

import dev.vexelray.maistro.agent.AgentEvent;
import dev.vexelray.maistro.agent.AgentListener;
import dev.vexelray.maistro.agent.AgentSession;
import dev.vexelray.maistro.agent.ClaudeCli;
import dev.vexelray.maistro.agent.JsonValue;
import dev.vexelray.maistro.agent.Suggestion;
import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.MapDelta;
import dev.vexelray.maistro.map.MapGraph;
import dev.vexelray.maistro.map.MapProtocol;
import dev.vexelray.maistro.map.Merge;
import dev.vexelray.maistro.map.Node;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The agent's side of the conversation: a Claude Code process for each strand ({@link Doc.Strand}), and what turns
 * their events into changes to the session.
 *
 * <h2>One lane per strand</h2>
 *
 * <p>Each strand, the main one and every branch, has a {@link Lane}: its own process, the tool turns it has open,
 * the permission asks it waits on, and the session id to resume it with. A lane's events change its own strand and
 * nothing else, except the main lane's map blocks, which grow the map; a branch's grow the branch's own map, which
 * stays apart until it is merged. The commands the human gives without naming a strand (send, interrupt, answer)
 * go to the strand on screen.
 *
 * <h2>Threads</h2>
 *
 * <p>Commands are called on workers. Starting a process is I/O, so a strand's first message starts it right there,
 * on the worker that is already off the frame loop, and later messages write one line to its stdin. Each process's
 * events arrive on its own reader thread, one at a time and in order ({@link AgentListener}), and each becomes one
 * commit to the {@link Model}, whose compare-and-set puts the lanes' commits in an order. Nothing here holds a lock
 * while committing, and nothing here is reached from a model listener.
 *
 * <h2>A process resumed when it goes, and ignored once replaced</h2>
 *
 * <p>A process lives for its strand. If it exits (a crash, a kill, an auth failure), the strand's next message starts
 * a new one with {@code --resume} and the session id the last one reported. A process this code closes (a new
 * conversation, an abandoned branch) still reports its end; its lane's generation has moved on by then, so that
 * report, and anything else it still says, is dropped.
 */
final class Conductor implements AutoCloseable {

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

    /** What the agent is told when the human says no. */
    static final String DENIED = "The human denied this in Maistro. Do not retry it; ask what they want instead.";

    /** How far from a node its neighbourhood reaches: the node and what it links to directly. */
    static final int NEIGHBOURHOOD = 1;

    private final Model model;
    private final Launch launch;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    Conductor(Model model, Launch launch) {
        this.model = model;
        this.launch = launch;
    }

    /** The main strand's lane. */
    Lane main() {
        return lane(Doc.MAIN);
    }

    /** Strand {@code id}'s lane, made the first time it is wanted. */
    Lane lane(String id) {
        return lanes.computeIfAbsent(id, Lane::new);
    }

    private Lane shownLane() {
        return lane(model.doc().shown().id());
    }

    // ------------------------------------------------------------------ commands, on workers

    /** Say {@code text} in the strand on screen. */
    void send(String text) {
        shownLane().send(text);
    }

    /** Stop the turn in progress in the strand on screen. */
    void interrupt() {
        shownLane().interrupt();
    }

    /** Answer an ask in the strand on screen: its buttons are in the transcript on screen. */
    void answer(long turn, boolean allow) {
        shownLane().answer(turn, allow);
    }

    /** Allow, and take a standing permission, for an ask in the strand on screen. */
    void answerAlways(long turn, int n) {
        shownLane().answerAlways(turn, n);
    }

    /**
     * Start a branch from the focused node of the map, and show it. The branch is given the node's neighbourhood
     * as its map and nothing of the conversation; its process starts with its first message.
     *
     * @return the new branch's id, or empty if no node is focused
     */
    String branch() {
        AtomicReference<String> made = new AtomicReference<>("");
        model.change(d -> {
            Optional<Node> node = d.mind().graph().focus().flatMap(d.mind().graph()::node);
            if (node.isEmpty()) {
                made.set("");
                return d.withStatus("Click a node in the map first: a branch starts from one.");
            }
            String id = d.nextBranchId();
            made.set(id);
            MapGraph given = d.mind().graph().neighbourhood(node.get().id(), NEIGHBOURHOOD);
            return d.withBranch(Doc.Strand.branch(id, node.get(), given))
                    .withStatus("Branch from " + node.get().shownLabel());
        });
        return made.get();
    }

    /** Close branch {@code id}: its process ends, and it goes, its staged map with it. Not the main strand. */
    boolean abandon(String id) {
        if (id.equals(Doc.MAIN)) {
            return false;
        }
        Lane l = lanes.remove(id);
        if (l != null) {
            l.close();
        }
        model.change(d -> d.strand(id)
                .map(s -> d.withoutBranch(id).withStatus("Abandoned the branch from " + s.label()))
                .orElse(d));
        return true;
    }

    /**
     * Merge branch {@code id} into the main strand: its staged map joins the map ({@link Merge}: a branch only adds,
     * and an id it reused for something else is renamed), the main transcript gets a note saying what came in, and
     * the main agent is told, with its next message, what the branch last said and what it added. Then the branch
     * goes, as Abandon. Refused while the branch's agent is mid-turn.
     *
     * @return whether it was merged
     */
    boolean merge(String id) {
        AtomicReference<Merge> merged = new AtomicReference<>();
        model.change(d -> {
            Optional<Doc.Strand> b = d.strand(id).filter(s -> !s.isMain());
            if (b.isEmpty()) {
                merged.set(null);
                return d;
            }
            Doc.Strand s = b.get();
            if (s.busy()) {
                merged.set(null);
                return d.withStatus("The branch is working. Interrupt it (Escape) or wait for its reply, then merge.");
            }
            Merge m = Merge.of(d.mind().graph(), s.graph(), id, d.mind().turn());
            merged.set(m);
            String note = mergeNote(s, m);
            return d.withoutBranch(id)
                    .withMind(mind -> m.isEmpty() ? mind : mind.with(m.graph()))
                    .withStrand(Doc.MAIN, x -> x.withTranscript(t -> t.append(n -> Turn.note(n, note, false)))
                            .toldNext(mergeNews(s, m)))
                    .withStatus(note);
        });
        if (merged.get() == null) {
            return false;
        }
        Lane l = lanes.remove(id);
        if (l != null) {
            l.close();
        }
        return true;
    }

    /** The longest stretch of a branch's last reply the main agent is told. */
    static final int NEWS_REPLY_MAX = 1500;

    /** What the main transcript says of a merge: {@code Merged the branch from X: +2 nodes, +1 links; ...}. */
    static String mergeNote(Doc.Strand branch, Merge m) {
        StringBuilder sb = new StringBuilder("Merged the branch from ").append(branch.label()).append(": ");
        if (m.isEmpty()) {
            sb.append("nothing new for the map");
        } else {
            sb.append('+').append(m.nodes()).append(m.nodes() == 1 ? " node" : " nodes")
                    .append(", +").append(m.links()).append(m.links() == 1 ? " link" : " links");
        }
        m.renamed().forEach((was, now) -> sb.append("; ").append(was).append(" came in as ").append(now));
        return sb.toString();
    }

    /**
     * What the main agent is told of a merge, ahead of its next message: which node the branch started from, what it
     * last said (cut to {@link #NEWS_REPLY_MAX}), and what it added to the map, as a {@code maistro-map} block. The
     * branch's history is not passed on; that is what a branch is for.
     */
    static String mergeNews(Doc.Strand branch, Merge m) {
        String last = "";
        for (Turn t : branch.transcript().turns()) {
            if (t.who() == Turn.Who.AGENT) {
                last = t.text();
            }
        }
        if (last.length() > NEWS_REPLY_MAX) {
            last = last.substring(0, NEWS_REPLY_MAX - 1) + "…";
        }
        StringBuilder sb = new StringBuilder("A branch of this conversation, started at the map node \"")
                .append(branch.label()).append("\" (").append(branch.from())
                .append("), ran in a session of its own and has been merged back. ");
        sb.append(last.isEmpty() ? "It said nothing." : "Its last reply:\n\n" + last);
        if (!m.isEmpty()) {
            sb.append("\n\nIt added to the map:\n\n").append(MapProtocol.render(m.added()));
        }
        if (!m.renamed().isEmpty()) {
            sb.append("\nIds it used for something else on the map were renamed: ");
            List<String> pairs = new ArrayList<>();
            m.renamed().forEach((was, now) -> pairs.add(was + " -> " + now));
            sb.append(String.join(", ", pairs)).append('.');
        }
        return sb.toString();
    }

    /** Put strand {@code id} on screen. */
    void show(String id) {
        model.change(d -> d.showing(id));
    }

    /**
     * Start a new conversation. Refused, with a word on the status line, while any strand's turn is running:
     * nothing the agent is doing is cut off without the human saying so (Escape). Otherwise every process ends, and
     * the session is reset with the panes kept. The conversation that ends was saved when its last turn did
     * ({@code Archive}).
     *
     * @return whether a new conversation started
     */
    boolean restart() {
        return switchTo(Map.of(), Doc::restarted);
    }

    /**
     * Carry on with saved conversation {@code c}: as {@link #restart}, but the session becomes {@code c}, and each
     * strand's next message starts Claude Code with {@code --resume} and that strand's session id.
     *
     * @return whether it was resumed; not while a turn is running
     */
    boolean resume(Conversation c) {
        Map<String, String> sessions = new LinkedHashMap<>();
        sessions.put(Doc.MAIN, c.agent().sessionId());
        for (Conversation.Branch b : c.branches()) {
            sessions.put(b.id(), b.agent().sessionId());
        }
        return switchTo(sessions, d -> d.resumed(c));
    }

    /** End every process, and make {@code next} the session, each strand resuming the session id it is given. */
    private boolean switchTo(Map<String, String> sessions, UnaryOperator<Doc> next) {
        if (model.doc().anyBusy()) {
            model.say("The agent is working. Interrupt it (Escape) or wait for the reply, then switch.");
            return false;
        }
        close();
        sessions.forEach((id, session) -> lane(id).resumeFrom(session));
        model.change(next);
        return true;
    }

    @Override
    public void close() {
        List<Lane> all = new ArrayList<>(lanes.values());
        lanes.clear();
        for (Lane l : all) {
            l.close();
        }
    }

    // ------------------------------------------------------------------ one strand's process

    /** A strand's process, and its bookkeeping. Its events change strand {@link #id} and nothing else. */
    final class Lane implements AgentListener {

        private final String id;
        /** Tool-use id to the transcript turn showing it. */
        private final Map<String, Long> tools = new ConcurrentHashMap<>();
        /** Tool turn id to the permission request it waits on: what answering it needs. */
        private final Map<Long, Asked> asks = new ConcurrentHashMap<>();

        private AgentSession session;
        private String lastSessionId = "";
        /** Which of this lane's processes is current; events from an older one are dropped. */
        private int generation;

        Lane(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        /** Apply {@code change} to the session with this lane's strand; unchanged if the strand has gone. */
        private void update(BiFunction<Doc, Doc.Strand, Doc> change) {
            model.change(d -> d.strand(id).map(s -> change.apply(d, s)).orElse(d));
        }

        /** Apply {@code change} to this lane's strand. */
        private void strand(UnaryOperator<Doc.Strand> change) {
            model.change(d -> d.withStrand(id, change));
        }

        private synchronized void resumeFrom(String sessionId) {
            lastSessionId = sessionId == null ? "" : sessionId;
        }

        /**
         * Say {@code text} to this strand's agent; starts its process the first time.
         *
         * <p>If the human picked a node, the message starts from it ({@link #fromNode}), the transcript shows the
         * text with a {@code from · <label>} chip, and the anchor is let go. A branch's first message also tells its
         * agent what it is and gives it its map ({@link #branchStart}). The anchor is read inside the same commit
         * that lets it go, so a click landing meanwhile is either in this message or kept for the next. A commit's
         * function may run more than once, and the last run is the one committed, so the prompt it leaves is the one
         * that matches the transcript.
         */
        void send(String text) {
            AtomicReference<String> prompt = new AtomicReference<>(text);
            update((d, s) -> {
                Optional<Node> from = d.mind().anchorNode();
                String body = from.map(n -> fromNode(d.mind().graph(), n, text)).orElse(text);
                if (!s.news().isEmpty()) {
                    body = String.join("\n\n", s.news()) + "\n\n" + body;
                }
                prompt.set(!s.isMain() && s.spoken() == 0 ? branchStart(s, body) : body);
                List<String> chips = new ArrayList<>();
                from.ifPresent(n -> chips.add("from · " + n.shownLabel()));
                if (!s.news().isEmpty()) {
                    int n = s.news().size();
                    chips.add("told · " + n + (n == 1 ? " merged branch" : " merged branches"));
                }
                Doc next = d.withStrand(id, x -> x.withTranscript(t -> t.append(n -> Turn.human(n, text)
                        .withChips(chips))).withActivity("thinking", true).told());
                return s.isMain()
                        ? next.withMind(Doc.Mind::spoke).withMeta(m -> m.titled(text))
                        : next.withMind(Doc.Mind::unanchored);
            });
            AgentSession s = sessionOrStart();
            if (s == null) {
                strand(x -> x.withActivity("not started", false));
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
                    .withPermissionPrompts()
                    .withModel(blankToNull(launch.model()))
                    .withPermissionMode(blankToNull(launch.permissionMode()))
                    .withWorkingDirectory(launch.cwd().isBlank() ? null : Path.of(launch.cwd()))
                    .withResume(blankToNull(lastSessionId));
            try {
                session = AgentSession.start(options, new Current(generation));
                LOG.info("started Claude Code for strand {} from {} ({})", id, exe, found.reason());
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

        /** End this lane's process; whatever it says after this is dropped. */
        void close() {
            AgentSession s;
            synchronized (this) {
                s = session;
                session = null;
                generation++;
            }
            tools.clear();
            asks.clear();
            if (s != null) {
                s.close();
            }
        }

        private synchronized boolean isCurrent(int g) {
            return g == generation;
        }

        /** Hands a process's events to this lane while that process is the lane's current one. */
        private final class Current implements AgentListener {

            private final int generation;

            Current(int generation) {
                this.generation = generation;
            }

            @Override
            public void onEvent(AgentEvent event) {
                if (isCurrent(generation)) {
                    event.to(Lane.this);
                }
            }
        }

        // ---- events, on the reader thread

        @Override
        public void onSessionInit(AgentEvent.SessionInit e) {
            remember(e.sessionId());
            strand(s -> s.withAgent(a -> a.started(e.sessionId(), e.model(), e.cwd())));
        }

        @Override
        public void onThinkingDelta(AgentEvent.ThinkingDelta e) {
            boolean already = model.doc().strand(id).map(s -> "thinking".equals(s.activity())).orElse(true);
            if (e.parentToolUseId().isEmpty() && !already) {
                strand(s -> s.withActivity("thinking", true));
            }
        }

        /** A piece of the reply being written: grow the live reply, or start one. */
        @Override
        public void onTextDelta(AgentEvent.TextDelta e) {
            if (!e.parentToolUseId().isEmpty()) {
                return;
            }
            strand(s -> {
                Turn live = s.transcript().liveReply();
                Doc.Strand next = live == null
                        ? s.withTranscript(t -> t.append(n -> Turn.agent(n, e.text(), true)))
                        : s.withTranscript(t -> t.change(live.id(), r -> r.withText(r.text() + e.text())));
                return next.withActivity("writing", true);
            });
        }

        /**
         * A whole text block of the reply. With partial messages on, the deltas already built it, so this is the
         * authoritative copy and ends the live reply; without them, it is the reply.
         *
         * <p>This is where a map grows: the block's {@code maistro-map} statements are applied, the transcript keeps
         * the prose, and the reply wears a chip saying what it added. The main strand's grow the map; a branch's
         * grow its own, staged, and its chips say {@code staged}.
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
            update((d, s) -> {
                MapGraph.Applied applied = s.isMain()
                        ? d.mind().graph().applyReport(parsed.delta(), d.mind().turn())
                        : s.graph().applyReport(parsed.delta(), s.spoken());
                Optional<String> before = (s.isMain() ? d.mind().graph() : s.graph()).focus();
                List<String> chips = s.isMain() ? chips(applied, before) : staged(chips(applied, before));
                Turn live = s.transcript().liveReply();
                Doc next = d.withStrand(id, x -> {
                    Doc.Strand told = live == null
                            ? x.withTranscript(t -> t.append(n -> Turn.agent(n, parsed.prose(), false)
                                    .withChips(chips)))
                            : x.withTranscript(t -> t.change(live.id(),
                                    r -> r.withText(parsed.prose()).withChips(chips).finished(false)));
                    return s.isMain() || parsed.delta().isEmpty() ? told : told.withGraph(applied.graph());
                });
                return s.isMain() && !parsed.delta().isEmpty() ? next.withMind(m -> m.with(applied.graph())) : next;
            });
        }

        @Override
        public void onToolUse(AgentEvent.ToolUse e) {
            if (!e.parentToolUseId().isEmpty()) {
                return;
            }
            String detail = describe(e.name(), e.input());
            strand(s -> {
                Turn live = s.transcript().liveReply();
                Doc.Strand settled = live == null ? s
                        : s.withTranscript(t -> t.change(live.id(), r -> r.finished(false)));
                Doc.Strand next = settled.withTranscript(t -> t.append(n -> Turn.tool(n, e.name(), detail)));
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
            strand(s -> s.withTranscript(t -> t.change(turn, r -> {
                String text = preview.isEmpty() ? r.text() : r.text() + "\n→ " + preview;
                return r.withText(text).finished(e.isError());
            })).withActivity("thinking", true));
        }

        /**
         * The agent wants to run a tool that needs the human's say-so. Its tool turn (or a new one, if the CLI named
         * no {@code tool_use} this process has seen) waits with Allow and Deny under it, and the CLI's own offers.
         * This returns at once: the CLI waits for the answer, the reader thread does not.
         */
        @Override
        public void onPermissionRequest(AgentEvent.PermissionRequest e) {
            Long known = e.toolUseId().isEmpty() ? null : tools.get(e.toolUseId());
            String detail = describe(e.toolName(), e.input());
            List<Suggestion> offers = e.suggestions();
            List<String> labels = offers.stream().map(Conductor::offerLabel).toList();
            Asked asked = new Asked(e.requestId(), e.toolName(), e.input(), offers);
            strand(s -> {
                boolean seen = known != null && s.transcript().turns().stream().anyMatch(t -> t.id() == known);
                Doc.Strand next = seen ? s
                        : s.withTranscript(t -> t.append(n -> Turn.tool(n, e.toolName(), detail)));
                long turn = seen ? known : next.transcript().last().id();
                // Recorded before the buttons can be drawn, so no click finds it missing. A commit's function may
                // run more than once, and the last run is the one committed, so each run drops what an earlier put.
                asks.values().remove(asked);
                asks.put(turn, asked);
                return next.withTranscript(t -> t.change(turn, r -> r.asking(labels)))
                        .withActivity("waiting for you: " + e.toolName(), true);
            });
        }

        /** A request Maistro has no answer for gets an error rather than silence, which would stall the CLI. */
        @Override
        public void onControlRequest(AgentEvent.ControlRequest e) {
            LOG.debug("refusing control request {} ({})", e.requestId(), e.subtype());
            AgentSession s = current();
            if (s != null) {
                s.refuse(e.requestId(), "Maistro does not handle " + e.subtype());
            }
        }

        /**
         * The human answered the tool turn {@code turn}: allow it to run, or deny it. On a worker. A second answer
         * to the same turn (a double click) does nothing.
         */
        void answer(long turn, boolean allow) {
            Asked a = asks.remove(turn);
            if (a == null) {
                return;
            }
            AgentSession s = current();
            boolean sent = s != null && (allow ? s.allow(a.requestId(), a.input()) : s.deny(a.requestId(), DENIED));
            if (!sent) {
                failed("Could not answer Claude Code: its process has gone.");
                return;
            }
            strand(x -> x.withTranscript(t -> t.change(turn,
                            r -> r.settled(allow ? Turn.Ask.ALLOWED : Turn.Ask.DENIED)))
                    .withActivity(waitingOr(x, turn, allow ? "running " + a.tool() : "thinking"), true));
        }

        /**
         * The human allowed the tool turn {@code turn} and took offer {@code n} with it: the tool runs, and the
         * CLI's own suggestion goes back unchanged so it stops asking for what that covers. The turn wears what was
         * set up, scope and all. On a worker; a second answer, or an offer that is not there, does nothing.
         */
        void answerAlways(long turn, int n) {
            Asked a = asks.get(turn);
            if (a == null || n < 0 || n >= a.offers().size() || !asks.remove(turn, a)) {
                return;
            }
            Suggestion offer = a.offers().get(n);
            AgentSession s = current();
            if (s == null || !s.allow(a.requestId(), a.input(), offer)) {
                failed("Could not answer Claude Code: its process has gone.");
                return;
            }
            LOG.info("allowed {} and set up: {}", a.tool(), offer.update().toJson());
            strand(x -> x.withTranscript(t -> t.change(turn, r -> {
                        List<String> chips = new ArrayList<>(r.chips());
                        chips.add(offerLabel(offer));
                        return r.settled(Turn.Ask.ALWAYS).withChips(chips);
                    }))
                    .withActivity(waitingOr(x, turn, "running " + a.tool()), true));
        }

        @Override
        public void onApiError(AgentEvent.ApiError e) {
            String what = e.text().isEmpty() ? e.error() : e.text();
            strand(s -> s.withTranscript(t -> t.append(n -> Turn.note(n, what, true))));
        }

        /** The end of a turn: everything still growing stops, and the totals move on. */
        @Override
        public void onResult(AgentEvent.Result e) {
            remember(e.sessionId());
            tools.clear();
            asks.clear();
            strand(s -> {
                Doc.Strand next = s.withTranscript(t -> t.settle())
                        .withActivity("idle", false)
                        .withAgent(a -> a.plus(e.costUsd(), e.numTurns(), e.usage().inputTokens(),
                                e.usage().outputTokens()));
                String why = e.resultText().isEmpty() ? e.subtype() : e.resultText();
                Turn last = s.transcript().last();
                boolean alreadySaid = last != null && last.who() == Turn.Who.NOTE && last.text().equals(why);
                if (!e.success() && !alreadySaid) {
                    next = next.withTranscript(t -> t.append(n -> Turn.note(n, "The turn failed: " + why, true)));
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
            asks.clear();
            synchronized (this) {
                session = null;
            }
            String note = e.exitCode() == 0
                    ? "Claude Code's process ended. The next message starts it again and resumes the conversation."
                    : "Claude Code's process ended with exit code " + e.exitCode()
                            + ". The next message starts it again and resumes the conversation.";
            strand(s -> s.withTranscript(t -> t.settle().append(n -> Turn.note(n, note, e.exitCode() != 0)))
                    .withActivity("stopped", false));
        }

        private void failed(String message) {
            update((d, s) -> d.withStrand(id, x -> x.withTranscript(t -> t.append(n -> Turn.note(n, message, true)))
                    .withActivity("not started", false)).withStatus(message));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What answering a permission request needs: its id, the input to let the tool run with, and the CLI's own
     * standing permissions on offer, in the order the turn shows them.
     */
    private record Asked(String requestId, String tool, JsonValue input, List<Suggestion> offers) {
    }

    /** An offer as a button says it; one written to a settings file says so, since it outlives the conversation. */
    static String offerLabel(Suggestion s) {
        return s.persistent() ? s.label() + " (saved to settings)" : s.label();
    }

    /** What {@code s}'s agent is doing once {@code answered} is: still waiting on another ask, or {@code otherwise}. */
    private static String waitingOr(Doc.Strand s, long answered, String otherwise) {
        for (Turn t : s.transcript().asking()) {
            if (t.id() != answered) {
                return "waiting for you: " + t.title();
            }
        }
        return otherwise;
    }

    /**
     * What a reply did to the map, as chips: {@code map · +3 nodes}, and {@code focus · kv-cache} when it moved the
     * focus from {@code before}.
     */
    static List<String> chips(MapGraph.Applied applied, Optional<String> before) {
        List<String> chips = new ArrayList<>();
        int nodes = applied.addedNodes().size();
        if (nodes > 0 || applied.addedEdges() > 0) {
            chips.add("map · +" + nodes + (nodes == 1 ? " node" : " nodes")
                    + (applied.addedEdges() > 0 ? ", +" + applied.addedEdges() + " links" : ""));
        }
        applied.graph().focus().filter(f -> !before.equals(Optional.of(f))).ifPresent(f -> chips.add("focus · " + f));
        return chips;
    }

    /** A branch's chips: what it added waits, staged, until it is merged. */
    private static List<String> staged(List<String> chips) {
        return chips.stream().map(c -> c.startsWith("map · ") ? "staged · " + c.substring(6) : c).toList();
    }

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

    /**
     * What a branch's agent is told before its first message: what a branch is, the map it was given, and that what
     * it adds is kept apart until the human merges it. It is told nothing of the conversation that grew the map.
     */
    static String branchStart(Doc.Strand branch, String text) {
        return "You are a branch of a larger conversation, started at the map node \"" + branch.label() + "\" ("
                + branch.from() + "). You see only this part of its map, not the conversation that grew it:\n\n"
                + MapProtocol.render(branch.graph())
                + "\nWork within it. Add to the map with maistro-map blocks as usual: what you add is kept apart "
                + "until the human merges this branch back.\n\n" + text;
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
