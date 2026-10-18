package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.archive.Summary;
import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * Everything Maistro knows about its session, in one immutable value: the conversation, what the agent is doing
 * right now, which panes are on show, what the agent's process has told us about itself, and the last thing the
 * status line was told.
 *
 * <h2>Why a record, and why one</h2>
 *
 * <p>A snapshot rather than a set of fields somebody mutates. The agent's reader thread streams into it, handlers
 * write it from workers, and the window, the status line and the remembered session read it, and a record handed
 * over by a single reference swap is the whole synchronisation story: there is no instant at which a reader sees
 * half an edit. Add fields here rather than adding state elsewhere.
 *
 * <h2>Strands</h2>
 *
 * <p>A conversation is one or more {@link Strand}s: the main one, first, and a branch for each line of thought
 * started from a node of the map. Each strand has its own transcript and its own Claude Code process; the map is
 * the main strand's, and a branch's changes to it wait, staged, in the branch. {@link #transcript()},
 * {@link #activity()}, {@link #busy()} and {@link #agent()} answer for the strand on screen, which is what the window
 * and a command acting on "this conversation" want; anything acting on one strand in particular names it.
 *
 * @param strands the main strand, first, then each branch, in the order they were started
 * @param showing which strand is on screen, by id
 * @param panes   which panes are on show
 * @param mind    the mind map
 * @param status  the last thing the status line was told
 * @param meta    which conversation this is: its id, title and when it started
 * @param library the list of saved conversations, when it is open
 */
record Doc(List<Strand> strands, String showing, Panes panes, Mind mind, String status, Meta meta,
           Library library) {

    /** The main strand's id. */
    static final String MAIN = "main";

    Doc {
        strands = List.copyOf(strands);
    }

    /**
     * One line of the conversation, with its own Claude Code process: the main one, or a branch started from a node.
     *
     * @param id         {@link #MAIN}, or {@code b1}, {@code b2}, ...
     * @param from       the node a branch was started from; empty for the main strand
     * @param label      what its tab says: the node's label for a branch
     * @param transcript what was said in it
     * @param activity   what its agent is doing, in a few words ({@code idle}, {@code thinking}, {@code running Bash})
     * @param busy       its agent has a turn in progress, so a second message waits and Escape interrupts
     * @param agent      what its agent's process said about itself
     * @param graph      a branch's own map: the neighbourhood it was given, grown by its replies, and not yet part
     *                   of the main map; empty for the main strand, whose replies grow {@link Doc#mind()} directly
     * @param news       what its agent is to be told ahead of the next message: for the main strand, the branches
     *                   merged into it since it last spoke, which it has not otherwise seen
     */
    record Strand(String id, String from, String label, Transcript transcript, String activity, boolean busy,
                  AgentInfo agent, MapGraph graph, List<String> news) {

        Strand {
            news = List.copyOf(news);
        }

        static final Strand MAIN_EMPTY =
                new Strand(MAIN, "", "Main", Transcript.empty(), "not started", false, AgentInfo.NONE, MapGraph.EMPTY,
                        List.of());

        boolean isMain() {
            return from.isEmpty();
        }

        /** A branch from {@code node}, given {@code neighbourhood} as its map. */
        static Strand branch(String id, dev.vexelray.maistro.map.Node node, MapGraph neighbourhood) {
            return new Strand(id, node.id(), node.shownLabel(), Transcript.empty(), "not started", false,
                    AgentInfo.NONE, neighbourhood, List.of());
        }

        /** How many times the human has spoken in it: the turn its own map's nodes are stamped with. */
        int spoken() {
            int n = 0;
            for (dev.vexelray.maistro.chat.Turn t : transcript.turns()) {
                if (t.who() == dev.vexelray.maistro.chat.Turn.Who.HUMAN) {
                    n++;
                }
            }
            return n;
        }

        Strand withTranscript(UnaryOperator<Transcript> change) {
            return new Strand(id, from, label, change.apply(transcript), activity, busy, agent, graph, news);
        }

        Strand withActivity(String value, boolean isBusy) {
            return new Strand(id, from, label, transcript, value, isBusy, agent, graph, news);
        }

        Strand withAgent(UnaryOperator<AgentInfo> change) {
            return new Strand(id, from, label, transcript, activity, busy, change.apply(agent), graph, news);
        }

        Strand withGraph(MapGraph value) {
            return new Strand(id, from, label, transcript, activity, busy, agent, value, news);
        }

        /** {@code item} to be told to this strand's agent with the next message. */
        Strand toldNext(String item) {
            List<String> more = new java.util.ArrayList<>(news);
            more.add(item);
            return new Strand(id, from, label, transcript, activity, busy, agent, graph, more);
        }

        /** The news has been told. */
        Strand told() {
            return news.isEmpty() ? this : new Strand(id, from, label, transcript, activity, busy, agent, graph,
                    List.of());
        }
    }

    // ---- the strands -------------------------------------------------------------------------------------------

    Strand main() {
        return strands.getFirst();
    }

    /** The strand with {@code id}, if it is still there. */
    Optional<Strand> strand(String id) {
        for (Strand s : strands) {
            if (s.id().equals(id)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** The strand on screen; the main one if {@link #showing} names one that has gone. */
    Strand shown() {
        return strand(showing).orElse(main());
    }

    /** The shown strand's transcript. */
    Transcript transcript() {
        return shown().transcript();
    }

    /** What the shown strand's agent is doing. */
    String activity() {
        return shown().activity();
    }

    /** The shown strand's agent has a turn in progress. */
    boolean busy() {
        return shown().busy();
    }

    /** What the shown strand's agent said about itself. */
    AgentInfo agent() {
        return shown().agent();
    }

    /** Some strand's agent has a turn in progress. */
    boolean anyBusy() {
        for (Strand s : strands) {
            if (s.busy()) {
                return true;
            }
        }
        return false;
    }

    /** Every strand's running totals, added up. */
    AgentInfo totals() {
        AgentInfo sum = AgentInfo.NONE;
        for (Strand s : strands) {
            AgentInfo a = s.agent();
            sum = sum.plus(a.costUsd(), a.turns(), a.inputTokens(), a.outputTokens());
        }
        return sum;
    }

    /** Change strand {@code id}; unchanged if it has gone (an event from a branch abandoned meanwhile). */
    Doc withStrand(String id, UnaryOperator<Strand> change) {
        List<Strand> next = new java.util.ArrayList<>(strands.size());
        for (Strand s : strands) {
            next.add(s.id().equals(id) ? change.apply(s) : s);
        }
        return new Doc(next, showing, panes, mind, status, meta, library);
    }

    /** Add a branch, and show it. */
    Doc withBranch(Strand branch) {
        List<Strand> next = new java.util.ArrayList<>(strands);
        next.add(branch);
        return new Doc(next, branch.id(), panes, mind, status, meta, library);
    }

    /** Take branch {@code id} away, its staged map with it, and show the main strand if it was on screen. */
    Doc withoutBranch(String id) {
        if (id.equals(MAIN)) {
            return this;
        }
        List<Strand> next = new java.util.ArrayList<>(strands);
        next.removeIf(s -> s.id().equals(id));
        return new Doc(next, showing.equals(id) ? MAIN : showing, panes, mind, status, meta, library);
    }

    /** Put strand {@code id} on screen; unchanged if there is no such strand. */
    Doc showing(String id) {
        return strand(id).isPresent() ? new Doc(strands, id, panes, mind, status, meta, library) : this;
    }

    /** An id for the next branch: one past the highest in use. */
    String nextBranchId() {
        int highest = 0;
        for (Strand s : strands) {
            if (s.id().startsWith("b")) {
                try {
                    highest = Math.max(highest, Integer.parseInt(s.id().substring(1)));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        }
        return "b" + (highest + 1);
    }

    /** Which of the three panes are on show. Each toggles on its own; the map takes whatever room is left. */
    record Panes(boolean conversation, boolean map, boolean context) {

        static final Panes ALL = new Panes(true, true, true);

        Panes toggle(Pane pane) {
            return pane.toggle(this);
        }

        boolean shows(Pane pane) {
            return pane.shown(this);
        }
    }

    /** A pane, with how to read and flip it on a {@link Panes}: a method on the constant rather than a switch. */
    enum Pane {
        CONVERSATION {
            @Override
            Panes toggle(Panes p) {
                return new Panes(!p.conversation(), p.map(), p.context());
            }

            @Override
            boolean shown(Panes p) {
                return p.conversation();
            }
        },
        MAP {
            @Override
            Panes toggle(Panes p) {
                return new Panes(p.conversation(), !p.map(), p.context());
            }

            @Override
            boolean shown(Panes p) {
                return p.map();
            }
        },
        CONTEXT {
            @Override
            Panes toggle(Panes p) {
                return new Panes(p.conversation(), p.map(), !p.context());
            }

            @Override
            boolean shown(Panes p) {
                return p.context();
            }
        };

        abstract Panes toggle(Panes p);

        abstract boolean shown(Panes p);
    }

    /**
     * What the agent's process has said about itself: the init line's session, model and folder, and the running
     * totals from each turn's result. Empty until the agent first speaks.
     */
    record AgentInfo(String sessionId, String model, String cwd, double costUsd, int turns, long inputTokens,
                     long outputTokens) {

        static final AgentInfo NONE = new AgentInfo("", "", "", 0, 0, 0, 0);

        AgentInfo started(String id, String modelName, String folder) {
            return new AgentInfo(id, modelName, folder, costUsd, turns, inputTokens, outputTokens);
        }

        AgentInfo plus(double cost, int moreTurns, long in, long out) {
            return new AgentInfo(sessionId, model, cwd, costUsd + cost, turns + moreTurns, inputTokens + in,
                    outputTokens + out);
        }
    }


    /**
     * The mind map: the concept graph the agent's replies grow, where each node sits, and how many times the human
     * has spoken (the turn a node is stamped with, so the last turn's nodes can be told apart).
     *
     * <p>The positions are kept beside the graph rather than worked out by the view, because a layout is only stable
     * against the one before it ({@link Layout#next}), and that history belongs to the session, not to the widgets.
     *
     * <p>The <b>anchor</b> is the node the human clicked, which the next message starts from; empty when there is
     * none. It is kept apart from the graph's focus because the agent moves the focus too, and a message should be
     * scoped to a node only when the human picked it.
     */
    record Mind(MapGraph graph, Map<String, Layout.Point> positions, int turn, String anchor) {

        static final Mind EMPTY = new Mind(MapGraph.EMPTY, Map.of(), 0, "");

        /** The width of one character of a node's label, and its padding, in layout units. */
        static final double CHAR = 7.5;
        static final double PAD = 24;
        static final double HEIGHT = 30;

        Mind {
            positions = Map.copyOf(positions);
        }

        /** A node's box in layout units: its label's rough width. The view draws at the same estimate. */
        static Layout.Size size(dev.vexelray.maistro.map.Node node) {
            return new Layout.Size(node.shownLabel().length() * CHAR + PAD, HEIGHT);
        }

        /** The human spoke: a new turn, and the anchor, used, is let go. */
        Mind spoke() {
            return new Mind(graph, positions, turn + 1, "");
        }

        /**
         * The human picked a node: focus it, and start the next message from it. Unchanged if there is no such node.
         * Nothing moves, so nothing is laid out again.
         */
        Mind picked(String id) {
            return graph.contains(id) ? new Mind(graph.focus(id), positions, turn, id) : this;
        }

        /** The next message starts from nowhere in particular. */
        Mind unanchored() {
            return new Mind(graph, positions, turn, "");
        }

        /** The node the next message starts from, if the human picked one. */
        Optional<dev.vexelray.maistro.map.Node> anchorNode() {
            return anchor.isEmpty() ? Optional.empty() : graph.node(anchor);
        }

        /**
         * The map as it would be with {@code branch}'s staged nodes and links on it, for drawing a branch: the main
         * map's nodes stay where they are and keep their labels, and the branch's new ones are laid out around them.
         * Nothing here is committed; merging is a separate step.
         */
        Mind staging(MapGraph branch) {
            MapGraph union = graph;
            for (dev.vexelray.maistro.map.Node n : branch.nodes()) {
                if (!union.contains(n.id())) {
                    union = union.withNode(n);
                }
            }
            for (dev.vexelray.maistro.map.Edge e : branch.edges()) {
                if (union.edge(e.from(), e.to()).isEmpty()) {
                    union = union.withEdge(e);
                }
            }
            return union == graph ? this : new Mind(union, Layout.next(positions, union, Mind::size), turn, anchor);
        }

        /**
         * The graph changed: lay it out against where everything was, and drop the anchor if its node went. Pure, so
         * it may run inside a commit.
         */
        Mind with(MapGraph next) {
            return new Mind(next, Layout.next(positions, next, Mind::size), turn,
                    next.contains(anchor) ? anchor : "");
        }
    }

    /**
     * Which conversation this is: Maistro's own id for it (which names its file), what it is called, and when it
     * started. The title is empty until the human first speaks.
     */
    record Meta(String id, String title, long started) {

        private static final DateTimeFormatter STAMP =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

        /**
         * A conversation starting at {@code now}: an id that sorts by time and is safe as a file name, with four
         * random hex digits so two started in one second do not collide.
         */
        static Meta startingAt(long now) {
            String id = STAMP.format(Instant.ofEpochMilli(now)) + "-"
                    + String.format(Locale.ROOT, "%04x", ThreadLocalRandom.current().nextInt(0x10000));
            return new Meta(id, "", now);
        }

        /** Called after {@code message}, if it has no title yet. */
        Meta titled(String message) {
            return title.isEmpty() ? new Meta(id, Conversation.titleFrom(message), started) : this;
        }
    }

    /**
     * The list of saved conversations (Ctrl+O), shown in place of the transcript while it is open. Its rows are read
     * from disk when it opens, so it is open and {@code loading} until they land.
     */
    record Library(boolean open, boolean loading, List<Summary> rows) {

        static final Library CLOSED = new Library(false, false, List.of());
        static final Library OPENING = new Library(true, true, List.of());

        Library {
            rows = List.copyOf(rows);
        }

        /** The rows landed; ignored if the list was closed meanwhile. */
        Library loaded(List<Summary> value) {
            return open ? new Library(true, false, value) : this;
        }
    }

    static Doc initial() {
        return new Doc(List.of(Strand.MAIN_EMPTY), MAIN, Panes.ALL, Mind.EMPTY, "",
                Meta.startingAt(System.currentTimeMillis()), Library.CLOSED);
    }

    /** A new conversation: nothing said, no map, no agent, and the panes as they are. */
    Doc restarted() {
        return initial().withPanes(panes).withStatus("New conversation");
    }

    /**
     * Conversation {@code c}, as it was saved, with the panes as they are and the list closed. The agent is not
     * running; the next message starts it with {@code --resume}.
     */
    Doc resumed(Conversation c) {
        List<Strand> next = new java.util.ArrayList<>();
        next.add(new Strand(MAIN, "", "Main", c.transcript(), "not started", false, info(c.agent()),
                MapGraph.EMPTY, c.news()));
        for (Conversation.Branch b : c.branches()) {
            next.add(new Strand(b.id(), b.from(), b.label(), b.transcript(), "not started", false, info(b.agent()),
                    b.graph(), List.of()));
        }
        return new Doc(next, MAIN, panes, new Mind(c.graph(), c.positions(), c.mindTurn(), ""),
                "Resumed: " + (c.title().isEmpty() ? "(untitled)" : c.title()),
                new Meta(c.id(), c.title(), c.started()), Library.CLOSED);
    }

    private static AgentInfo info(Conversation.Agent a) {
        return new AgentInfo(a.sessionId(), a.model(), a.cwd(), a.costUsd(), a.turns(), a.inputTokens(),
                a.outputTokens());
    }

    Doc withMeta(UnaryOperator<Meta> change) {
        return new Doc(strands, showing, panes, mind, status, change.apply(meta), library);
    }

    Doc withLibrary(UnaryOperator<Library> change) {
        return new Doc(strands, showing, panes, mind, status, meta, change.apply(library));
    }

    Doc withPanes(Panes value) {
        return new Doc(strands, showing, value, mind, status, meta, library);
    }

    Doc withMind(UnaryOperator<Mind> change) {
        return new Doc(strands, showing, panes, change.apply(mind), status, meta, library);
    }

    Doc withStatus(String value) {
        return new Doc(strands, showing, panes, mind, value, meta, library);
    }
}
