package dev.vexelray.maistro.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The conversation's concept graph, as one immutable value: every operation returns a new graph and leaves this one as
 * it was, as the application's {@code Doc} is replaced rather than mutated.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Merges are idempotent by id.</b> Adding a node that exists updates its label, kind and surprise and keeps
 *       the turn that first added it. Adding an edge that exists keeps its turn and takes a new label only if one is
 *       given.</li>
 *   <li><b>A carve sticks.</b> Carving a node removes it and its edges and records the id with a reason. Adding a
 *       carved node, or an edge to one, is refused until {@link #uncarve(String)}.</li>
 *   <li><b>Insertion order is kept</b>, so iteration (and so {@link Layout}) is deterministic.</li>
 *   <li><b>Edges never dangle.</b> An edge whose ends are not both present is refused.</li>
 * </ul>
 */
public final class MapGraph {

    /** The graph with nothing in it. */
    public static final MapGraph EMPTY = new MapGraph(Map.of(), Map.of(), Map.of(), null);

    private final Map<String, Node> nodes;
    private final Map<String, Edge> edges;
    private final Map<String, Carve> carves;
    private final String focus;

    private MapGraph(Map<String, Node> nodes, Map<String, Edge> edges, Map<String, Carve> carves, String focus) {
        this.nodes = nodes;
        this.edges = edges;
        this.carves = carves;
        this.focus = focus;
    }

    // ---- queries -------------------------------------------------------------------------------------------------

    /** The nodes, in the order they were first added. */
    public Collection<Node> nodes() {
        return nodes.values();
    }

    /** The edges, in the order they were first added. */
    public Collection<Edge> edges() {
        return edges.values();
    }

    /** The carves in force, in the order they were made. */
    public Collection<Carve> carves() {
        return carves.values();
    }

    public Optional<Node> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Optional<Edge> edge(String from, String to) {
        return Optional.ofNullable(edges.get(Edge.key(from, to)));
    }

    public Optional<Carve> carve(String id) {
        return Optional.ofNullable(carves.get(id));
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    public boolean isCarved(String id) {
        return carves.containsKey(id);
    }

    /** The focused node's id, if a node is focused. */
    public Optional<String> focus() {
        return Optional.ofNullable(focus);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public int carveCount() {
        return carves.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** The highest turn any node was added in, or -1 for an empty graph. */
    public int latestTurn() {
        int t = -1;
        for (Node n : nodes.values()) {
            t = Math.max(t, n.turn());
        }
        return t;
    }

    /** The nodes added in {@code turn}, in order. */
    public List<Node> addedIn(int turn) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.turn() == turn) {
                out.add(n);
            }
        }
        return out;
    }

    /** The nodes added in the latest turn: the ones the view outlines "from last turn". */
    public List<Node> addedInLatestTurn() {
        return isEmpty() ? List.of() : addedIn(latestTurn());
    }

    /** The ids joined to {@code id} by an edge in either direction, in edge order, without repeats. */
    public List<String> neighbours(String id) {
        Set<String> out = new LinkedHashSet<>();
        for (Edge e : edges.values()) {
            if (e.touches(id)) {
                out.add(e.other(id));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The part of the graph within {@code depth} edges of {@code id} (edges followed in either direction), with the
     * edges among those nodes and this graph's carves: what a turn forked from that node is given. Empty if the node
     * is not present. Depth 0 is the node alone.
     */
    public MapGraph neighbourhood(String id, int depth) {
        if (!nodes.containsKey(id)) {
            return new MapGraph(Map.of(), Map.of(), carves, null);
        }
        Map<String, List<String>> adjacency = adjacency();
        Map<String, Integer> dist = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        dist.put(id, 0);
        queue.add(id);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            int d = dist.get(at);
            if (d >= depth) {
                continue;
            }
            for (String next : adjacency.getOrDefault(at, List.of())) {
                if (!dist.containsKey(next)) {
                    dist.put(next, d + 1);
                    queue.add(next);
                }
            }
        }
        Map<String, Node> keptNodes = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            if (dist.containsKey(n.id())) {
                keptNodes.put(n.id(), n);
            }
        }
        Map<String, Edge> keptEdges = new LinkedHashMap<>();
        for (Edge e : edges.values()) {
            if (keptNodes.containsKey(e.from()) && keptNodes.containsKey(e.to())) {
                keptEdges.put(e.key(), e);
            }
        }
        return new MapGraph(freeze(keptNodes), freeze(keptEdges), carves, id);
    }

    private Map<String, List<String>> adjacency() {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge e : edges.values()) {
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        }
        return adjacency;
    }

    // ---- operations ----------------------------------------------------------------------------------------------

    /** This graph with {@code node} added or merged; unchanged if the id is carved. */
    public MapGraph withNode(Node node) {
        Builder b = new Builder(this);
        b.node(node);
        return b.build();
    }

    /** This graph with {@code edge} added or merged; unchanged if either end is missing or carved. */
    public MapGraph withEdge(Edge edge) {
        Builder b = new Builder(this);
        b.edge(edge);
        return b.build();
    }

    /** This graph without the node {@code id} and its edges, and with the id carved for {@code reason}. */
    public MapGraph carve(String id, String reason, int turn) {
        Builder b = new Builder(this);
        b.carve(new Carve(id, reason, turn));
        return b.build();
    }

    /** This graph with the carve on {@code id} lifted, so it may be added again. Removed nodes do not come back. */
    public MapGraph uncarve(String id) {
        if (!carves.containsKey(id)) {
            return this;
        }
        Map<String, Carve> c = new LinkedHashMap<>(carves);
        c.remove(id);
        return new MapGraph(nodes, edges, freeze(c), focus);
    }

    /** This graph focused on {@code id}; unchanged if there is no such node. */
    public MapGraph focus(String id) {
        return nodes.containsKey(id) ? new MapGraph(nodes, edges, carves, id) : this;
    }

    /** This graph with no node focused. */
    public MapGraph clearFocus() {
        return focus == null ? this : new MapGraph(nodes, edges, carves, null);
    }

    /** {@link #applyReport(MapDelta, int)}'s graph. */
    public MapGraph apply(MapDelta delta, int turn) {
        return applyReport(delta, turn).graph();
    }

    /**
     * This graph with a reply's delta applied as turn {@code turn}: carves first, then nodes, then edges, then focus.
     * An edge naming a node the delta did not declare creates it as a concept labelled by its id (models forget), unless
     * that id is carved.
     */
    public Applied applyReport(MapDelta delta, int turn) {
        Builder b = new Builder(this);
        for (MapDelta.CarveSpec c : delta.carves()) {
            b.carve(new Carve(c.id(), c.reason(), turn));
        }
        for (MapDelta.NodeSpec n : delta.nodes()) {
            b.node(n.at(turn));
        }
        for (MapDelta.EdgeSpec e : delta.edges()) {
            for (String end : List.of(e.from(), e.to())) {
                if (!b.nodes.containsKey(end) && !b.carves.containsKey(end)) {
                    b.node(Node.of(end, "", turn));
                }
            }
            b.edge(new Edge(e.from(), e.to(), e.label(), turn));
        }
        delta.focusId().ifPresent(id -> {
            if (b.nodes.containsKey(id)) {
                b.focus = id;
            } else {
                b.refused.add("focus " + id + ": no such node");
            }
        });
        return new Applied(b.build(), List.copyOf(b.addedNodes), b.addedEdges, List.copyOf(b.refused));
    }

    /**
     * What applying a delta did, for the reply's chips ({@code map - +3 nodes}).
     *
     * @param graph      the new graph
     * @param addedNodes ids of nodes that were new (not merges), in order
     * @param addedEdges how many edges were new
     * @param refused    one line per thing not applied, and why (mostly carves)
     */
    public record Applied(MapGraph graph, List<String> addedNodes, int addedEdges, List<String> refused) {
    }

    // ---- building --------------------------------------------------------------------------------------------------

    private static <V> Map<String, V> freeze(Map<String, V> m) {
        return Collections.unmodifiableMap(m);
    }

    /** Mutable scratch for one operation; never escapes. */
    private static final class Builder {
        final Map<String, Node> nodes;
        final Map<String, Edge> edges;
        final Map<String, Carve> carves;
        String focus;
        final List<String> addedNodes = new ArrayList<>();
        int addedEdges;
        final List<String> refused = new ArrayList<>();

        Builder(MapGraph g) {
            nodes = new LinkedHashMap<>(g.nodes);
            edges = new LinkedHashMap<>(g.edges);
            carves = new LinkedHashMap<>(g.carves);
            focus = g.focus;
        }

        void node(Node n) {
            if (carves.containsKey(n.id())) {
                refused.add("node " + n.id() + ": carved (" + carves.get(n.id()).reason() + ")");
                return;
            }
            Node old = nodes.get(n.id());
            if (old == null) {
                nodes.put(n.id(), n);
                addedNodes.add(n.id());
            } else {
                String label = n.label().isEmpty() ? old.label() : n.label();
                nodes.put(n.id(), new Node(n.id(), label, n.kind(), old.turn(), n.surprise()));
            }
        }

        void edge(Edge e) {
            if (!nodes.containsKey(e.from()) || !nodes.containsKey(e.to())) {
                refused.add("edge " + e.key() + ": an end is missing or carved");
                return;
            }
            Edge old = edges.get(e.key());
            if (old == null) {
                edges.put(e.key(), e);
                addedEdges++;
            } else if (!e.label().isEmpty()) {
                edges.put(e.key(), new Edge(e.from(), e.to(), e.label(), old.turn()));
            }
        }

        void carve(Carve c) {
            nodes.remove(c.id());
            edges.values().removeIf(e -> e.touches(c.id()));
            carves.put(c.id(), c);
            if (c.id().equals(focus)) {
                focus = null;
            }
        }

        MapGraph build() {
            return new MapGraph(freeze(nodes), freeze(edges), freeze(carves), focus);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MapGraph g && nodes.equals(g.nodes) && edges.equals(g.edges) && carves.equals(g.carves)
                && java.util.Objects.equals(focus, g.focus);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nodes, edges, carves, focus);
    }

    @Override
    public String toString() {
        return "MapGraph[" + nodes.size() + " nodes, " + edges.size() + " edges, " + carves.size() + " carves"
                + (focus == null ? "" : ", focus " + focus) + "]";
    }
}
