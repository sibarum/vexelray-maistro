package dev.vexelray.maistro.archive;

import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;

import java.util.List;
import java.util.Map;

/**
 * One conversation as it is kept on disk: what Maistro shows of it, which Claude Code's own session does not have
 * (the transcript as Maistro drew it, the map and where its nodes sat), and the Claude Code session id that
 * {@code --resume} needs to bring back the agent's side.
 *
 * <p>A value with no {@code Gui} and no file in it; {@link ConversationCodec} turns it into JSON and back, and the
 * application decides where the JSON goes.
 *
 * @param id        Maistro's own id for the conversation, which names its file; never blank
 * @param title     what the conversation is called in a list: the first line of the first message
 * @param started   when it started, epoch milliseconds
 * @param saved     when it was last saved, epoch milliseconds
 * @param agent     the Claude Code session and its running totals
 * @param transcript the conversation, nothing in it live
 * @param graph     the mind map
 * @param positions where each node of the map sat
 * @param mindTurn  how many times the human had spoken, the turn the map's nodes are stamped against
 * @param branches  the branches still open, each with its own transcript, agent and staged map
 */
public record Conversation(String id, String title, long started, long saved, Agent agent, Transcript transcript,
                           MapGraph graph, Map<String, Layout.Point> positions, int mindTurn,
                           List<Branch> branches) {

    /** A conversation with no branches. */
    public Conversation(String id, String title, long started, long saved, Agent agent, Transcript transcript,
                        MapGraph graph, Map<String, Layout.Point> positions, int mindTurn) {
        this(id, title, started, saved, agent, transcript, graph, positions, mindTurn, List.of());
    }

    /**
     * A branch started from a node of the map, with its own Claude Code session.
     *
     * @param from  the node it was started from
     * @param label what its tab says
     * @param graph its own map: the neighbourhood it was given, grown by its replies, not yet merged
     */
    public record Branch(String id, String from, String label, Agent agent, Transcript transcript, MapGraph graph) {

        public Branch {
            if (id == null || id.isBlank() || from == null || from.isBlank()) {
                throw new IllegalArgumentException("a branch needs an id and the node it started from");
            }
            label = label == null ? "" : label;
            agent = agent == null ? Agent.NONE : agent;
            transcript = transcript == null ? Transcript.empty() : transcript;
            graph = graph == null ? MapGraph.EMPTY : graph;
        }
    }

    /**
     * The agent's side: the Claude Code session to resume, and its totals.
     *
     * @param sessionId empty if the agent never started
     */
    public record Agent(String sessionId, String model, String cwd, double costUsd, int turns, long inputTokens,
                        long outputTokens) {

        public static final Agent NONE = new Agent("", "", "", 0, 0, 0, 0);

        public Agent {
            sessionId = sessionId == null ? "" : sessionId;
            model = model == null ? "" : model;
            cwd = cwd == null ? "" : cwd;
        }
    }

    public Conversation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a conversation needs an id");
        }
        title = title == null ? "" : title;
        agent = agent == null ? Agent.NONE : agent;
        transcript = transcript == null ? Transcript.empty() : transcript;
        graph = graph == null ? MapGraph.EMPTY : graph;
        positions = positions == null ? Map.of() : Map.copyOf(positions);
        branches = branches == null ? List.of() : List.copyOf(branches);
    }

    /** The longest title a list shows. */
    public static final int TITLE_MAX = 60;

    /** A title from a first message: its first non-blank line, cut to {@link #TITLE_MAX} characters. */
    public static String titleFrom(String message) {
        for (String line : message.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX - 1).stripTrailing() + "…";
            }
        }
        return "";
    }
}
