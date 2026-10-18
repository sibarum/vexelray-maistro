package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * @param transcript the conversation so far
 * @param activity   what the agent is doing, in a few words ({@code idle}, {@code thinking}, {@code running Bash})
 * @param busy       the agent has a turn in progress, so a second message waits and Escape interrupts
 * @param panes      which panes are on show
 * @param agent      what the agent's process said about itself
 * @param mind       the mind map
 * @param status     the last thing the status line was told
 * @param meta       which conversation this is: its id, title and when it started
 */
record Doc(Transcript transcript, String activity, boolean busy, Panes panes, AgentInfo agent, Mind mind,
           String status, Meta meta) {

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

    static Doc initial() {
        return new Doc(Transcript.empty(), "not started", false, Panes.ALL, AgentInfo.NONE, Mind.EMPTY, "",
                Meta.startingAt(System.currentTimeMillis()));
    }

    /** A new conversation: nothing said, no map, no agent, and the panes as they are. */
    Doc restarted() {
        return initial().withPanes(panes).withStatus("New conversation");
    }

    Doc withMeta(UnaryOperator<Meta> change) {
        return new Doc(transcript, activity, busy, panes, agent, mind, status, change.apply(meta));
    }

    Doc withTranscript(UnaryOperator<Transcript> change) {
        return new Doc(change.apply(transcript), activity, busy, panes, agent, mind, status, meta);
    }

    Doc withActivity(String value, boolean isBusy) {
        return new Doc(transcript, value, isBusy, panes, agent, mind, status, meta);
    }

    Doc withPanes(Panes value) {
        return new Doc(transcript, activity, busy, value, agent, mind, status, meta);
    }

    Doc withAgent(UnaryOperator<AgentInfo> change) {
        return new Doc(transcript, activity, busy, panes, change.apply(agent), mind, status, meta);
    }

    Doc withMind(UnaryOperator<Mind> change) {
        return new Doc(transcript, activity, busy, panes, agent, change.apply(mind), status, meta);
    }

    Doc withStatus(String value) {
        return new Doc(transcript, activity, busy, panes, agent, mind, value, meta);
    }
}
