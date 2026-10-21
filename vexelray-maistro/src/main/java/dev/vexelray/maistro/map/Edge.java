package dev.vexelray.maistro.map;

/**
 * A directed link between two nodes. Identity is the ordered pair, so at most one edge runs from one node to another.
 *
 * @param from  the source node's id
 * @param to    the target node's id
 * @param label optional text on the edge; never null, empty when there is none
 * @param turn  the turn that first added it; a merge never changes it
 */
public record Edge(String from, String to, String label, int turn) {

    public Edge {
        from = Ids.check(from);
        to = Ids.check(to);
        label = label == null ? "" : label;
    }

    /** The key that makes this edge unique in a graph. */
    public String key() {
        return key(from, to);
    }

    /** The key of the edge from {@code from} to {@code to}. */
    public static String key(String from, String to) {
        return from + " -> " + to;
    }

    /** Whether this edge touches the node {@code id} at either end. */
    public boolean touches(String id) {
        return from.equals(id) || to.equals(id);
    }

    /** The end that is not {@code id} (for a self-loop, {@code id} itself). */
    public String other(String id) {
        return from.equals(id) ? to : from;
    }
}
