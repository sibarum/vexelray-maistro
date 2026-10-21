package dev.vexelray.maistro.map;

import java.util.List;
import java.util.Optional;

/**
 * What one reply says about the map, before it is applied: the parsed {@code maistro-map} block(s), with no turn yet.
 * {@link MapGraph#apply(MapDelta, int)} stamps the turn on.
 *
 * @param nodes    nodes to add or merge, in the order they were written
 * @param edges    edges to add or merge, in the order they were written
 * @param carves   nodes to strike out, with reasons
 * @param focus    the node to focus, or null to leave the focus as it is
 * @param problems lines that were skipped or only partly understood; never applied silently, never fatal
 */
public record MapDelta(List<NodeSpec> nodes, List<EdgeSpec> edges, List<CarveSpec> carves, String focus,
                       List<Problem> problems) {

    /** A delta that changes nothing. */
    public static final MapDelta EMPTY = new MapDelta(List.of(), List.of(), List.of(), null, List.of());

    public MapDelta {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        carves = List.copyOf(carves);
        problems = List.copyOf(problems);
    }

    /** A node line: {@code node <id> "<label>" [kind] [surprise]}. */
    public record NodeSpec(String id, String label, Kind kind, double surprise) {
        public NodeSpec {
            id = Ids.check(id);
        }

        Node at(int turn) {
            return new Node(id, label, kind, turn, surprise);
        }
    }

    /** An edge line: {@code edge <from> <to> ["label"]}. */
    public record EdgeSpec(String from, String to, String label) {
        public EdgeSpec {
            from = Ids.check(from);
            to = Ids.check(to);
        }
    }

    /** A carve line: {@code carve <id> ["reason"]}. */
    public record CarveSpec(String id, String reason) {
        public CarveSpec {
            id = Ids.check(id);
        }
    }

    /**
     * A line the parser could not use as written.
     *
     * @param line   1-based line number within the whole reply
     * @param text   the line as written
     * @param reason what was wrong, and what (if anything) was still taken from it
     */
    public record Problem(int line, String text, String reason) {
    }

    /** The focus, if the delta sets one. */
    public Optional<String> focusId() {
        return Optional.ofNullable(focus);
    }

    /** Whether this delta would change nothing. */
    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty() && carves.isEmpty() && focus == null;
    }
}
