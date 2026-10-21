package dev.vexelray.maistro.map;

/**
 * One node of the concept graph.
 *
 * @param id       stable identity; merges and edges refer to it. Non-blank, no whitespace.
 * @param label    what the view shows; never null (an empty label is shown as the id by convention)
 * @param kind     what the node stands for
 * @param turn     the conversation turn that first added it; a merge never changes it
 * @param surprise 0 (settled, expected) to 1 (surprising); clamped, NaN reads as 0
 */
public record Node(String id, String label, Kind kind, int turn, double surprise) {

    public Node {
        id = Ids.check(id);
        label = label == null ? "" : label;
        kind = kind == null ? Kind.CONCEPT : kind;
        surprise = Double.isNaN(surprise) ? 0 : Math.max(0, Math.min(1, surprise));
    }

    /** A concept node with no surprise. */
    public static Node of(String id, String label, int turn) {
        return new Node(id, label, Kind.CONCEPT, turn, 0);
    }

    /** The label to draw: the label, or the id when there is none. */
    public String shownLabel() {
        return label.isBlank() ? id : label;
    }
}
