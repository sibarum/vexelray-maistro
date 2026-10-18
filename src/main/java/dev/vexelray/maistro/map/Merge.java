package dev.vexelray.maistro.map;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A branch's map merged into the main one, worked out as a value: what the main map becomes, what was added, and
 * which of the branch's ids had to be renamed on the way.
 *
 * <h2>A branch only adds</h2>
 *
 * <p>A branch is given a neighbourhood of the map and nothing else, so it cannot know what an id means elsewhere on
 * the map, and it must not change anything there. So:
 *
 * <ul>
 *   <li>A branch node the main map has <em>as it is</em> (same label and kind) is that node: nothing to do.</li>
 *   <li>A branch node whose id the main map uses for something else (another label or kind: the branch relabelled a
 *       node it was given, or named a new idea with an id taken elsewhere) comes in as a new node,
 *       {@code id~<suffix>}, and every link of the branch's to it follows. It is listed in {@link #renamed}.</li>
 *   <li>Any other branch node comes in as it is, stamped with the main conversation's turn, so it is drawn as new.</li>
 *   <li>A branch link the main map does not have comes in, between the nodes as renamed.</li>
 *   <li>Nothing carved on the main map comes back, and a branch's own carves and focus are not merged.</li>
 * </ul>
 *
 * @param graph   the main map with the branch merged in
 * @param added   what was added, with the nodes its links run between, for telling the main agent
 * @param renamed branch id to the id it was given on the main map
 * @param nodes   how many nodes were added
 * @param links   how many links were added
 */
public record Merge(MapGraph graph, MapGraph added, Map<String, String> renamed, int nodes, int links) {

    public Merge {
        renamed = Collections.unmodifiableMap(new LinkedHashMap<>(renamed));
    }

    /** {@code branch} merged into {@code main}; new nodes and links are stamped {@code turn}. */
    public static Merge of(MapGraph main, MapGraph branch, String suffix, int turn) {
        MapGraph result = main;
        MapGraph added = MapGraph.EMPTY;
        Map<String, String> renamed = new LinkedHashMap<>();
        int nodes = 0;
        for (Node n : branch.nodes()) {
            Node there = main.node(n.id()).orElse(null);
            if (there != null && there.label().equals(n.label()) && there.kind() == n.kind()) {
                continue;
            }
            String id = there == null ? n.id() : free(result, n.id() + "~" + suffix);
            if (result.isCarved(id)) {
                continue;
            }
            Node in = new Node(id, n.label(), n.kind(), turn, n.surprise());
            result = result.withNode(in);
            added = added.withNode(in);
            nodes++;
            if (!id.equals(n.id())) {
                renamed.put(n.id(), id);
            }
        }
        int links = 0;
        for (Edge e : branch.edges()) {
            String from = renamed.getOrDefault(e.from(), e.from());
            String to = renamed.getOrDefault(e.to(), e.to());
            if (!result.contains(from) || !result.contains(to) || result.edge(from, to).isPresent()) {
                continue;
            }
            result = result.withEdge(new Edge(from, to, e.label(), turn));
            for (String end : new String[] {from, to}) {
                if (!added.contains(end)) {
                    added = added.withNode(result.node(end).orElseThrow());
                }
            }
            added = added.withEdge(new Edge(from, to, e.label(), turn));
            links++;
        }
        return new Merge(result, added, renamed, nodes, links);
    }

    /** {@code id}, or {@code id-2}, {@code id-3}... : the first the graph neither has nor has carved. */
    private static String free(MapGraph g, String id) {
        String candidate = id;
        for (int i = 2; g.contains(candidate) || g.isCarved(candidate); i++) {
            candidate = id + "-" + i;
        }
        return candidate;
    }

    /** Nothing came in. */
    public boolean isEmpty() {
        return nodes == 0 && links == 0;
    }
}
