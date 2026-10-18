package dev.vexelray.maistro.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Where each node of the mind map sits, as a pure function of where they sat before, the graph, and how big each node
 * is. Positions are node centres in abstract layout units; the view scales {@link #fit} to its stage.
 *
 * <h2>How</h2>
 *
 * <ol>
 *   <li><b>Keep.</b> A node that had a position keeps it as its start.</li>
 *   <li><b>Place.</b> A new node starts beside its first already-placed neighbour (in edge order), at an angle
 *       hashed from its id; with no placed neighbour, on a ring just outside the existing nodes; the very first node
 *       at the origin.</li>
 *   <li><b>Relax</b> (only when there are new nodes): {@value #ITERATIONS} rounds of force-directed relaxation
 *       (Fruchterman–Reingold repulsion between every pair, attraction along edges, weak gravity to the centroid),
 *       with a step cap that cools linearly to zero. Only new nodes move; old nodes stay pinned and act on the new
 *       ones as fixed anchors, and forces between two old nodes are not computed at all, since the old part settled
 *       in an earlier call. That is what keeps the map from reshuffling when a turn adds a node. On a fresh graph
 *       every node is new, so this is a plain force layout.</li>
 *   <li><b>Separate.</b> Up to {@value #SEPARATION_PASSES} passes push every overlapping pair of boxes (with a
 *       {@value #GAP}-unit gap) apart along the axis that overlaps least; a new node takes most of the push, an old
 *       one only {@link #OLD_WEIGHT} of it. This is the only way an old node moves.</li>
 * </ol>
 *
 * <p>Cost: relaxation is O(ITERATIONS · (n² + e)), separation O(passes · n²); for the tens of nodes a conversation
 * makes that is well under a millisecond's arithmetic, fine for a worker per turn. Deterministic: no clock, no
 * {@code Random}, iteration in the graph's insertion order and a fixed hash seed, so the same inputs always give the
 * same positions.
 */
public final class Layout {

    /** The resting length of an edge between two point-sized nodes, in layout units. */
    public static final double EDGE_LENGTH = 120;
    /** Rounds of force relaxation per call. */
    public static final int ITERATIONS = 80;
    /** Most collision passes per call. */
    public static final int SEPARATION_PASSES = 200;
    /** Clear space kept between node boxes. */
    public static final double GAP = 8;
    /**
     * The share of an overlap an old node takes, against 1 for a new node: an old node gives way only a little,
     * and two old nodes (whose sizes changed) split it evenly.
     */
    public static final double OLD_WEIGHT = 0.02;

    private static final double GRAVITY = 0.01;
    private static final long SEED = 0x6D61697374726FL; // "maistro"

    /** A node centre. */
    public record Point(double x, double y) {
    }

    /** A node's box, width by height, in layout units. */
    public record Size(double width, double height) {
    }

    /** An axis-aligned bounding box. */
    public record Box(double minX, double minY, double maxX, double maxY) {
        public double width() {
            return maxX - minX;
        }

        public double height() {
            return maxY - minY;
        }

        public Point centre() {
            return new Point((minX + maxX) / 2, (minY + maxY) / 2);
        }

        /**
         * The uniform scale that fits this box, plus {@code margin} stage units on every side, inside a stage of
         * {@code stageWidth} by {@code stageHeight}. 1 for an empty box.
         */
        public double scaleToFit(double stageWidth, double stageHeight, double margin) {
            double w = width();
            double h = height();
            if (w <= 0 && h <= 0) {
                return 1;
            }
            double sx = w <= 0 ? Double.MAX_VALUE : Math.max(0, stageWidth - 2 * margin) / w;
            double sy = h <= 0 ? Double.MAX_VALUE : Math.max(0, stageHeight - 2 * margin) / h;
            return Math.min(sx, sy);
        }
    }

    private Layout() {
    }

    /**
     * The next positions for {@code graph}. {@code previous} may hold ids no longer in the graph (they are dropped)
     * and may lack new ones (they are placed). The result has exactly the graph's nodes, in its order.
     */
    public static Map<String, Point> next(Map<String, Point> previous, MapGraph graph, Function<Node, Size> sizes) {
        List<Node> nodes = new ArrayList<>(graph.nodes());
        int n = nodes.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodes.get(i).id(), i);
        }
        double[] x = new double[n];
        double[] y = new double[n];
        double[] w = new double[n];
        double[] h = new double[n];
        boolean[] placed = new boolean[n];
        List<List<Integer>> adjacent = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacent.add(new ArrayList<>());
        }
        List<int[]> edges = new ArrayList<>();
        for (Edge e : graph.edges()) {
            int a = index.get(e.from());
            int b = index.get(e.to());
            if (a != b) {
                edges.add(new int[] {a, b});
                adjacent.get(a).add(b);
                adjacent.get(b).add(a);
            }
        }

        boolean anyNew = false;
        boolean[] isNew = new boolean[n];
        for (int i = 0; i < n; i++) {
            Node node = nodes.get(i);
            Size s = sizes.apply(node);
            w[i] = Math.max(0, s.width());
            h[i] = Math.max(0, s.height());
            Point p = previous.get(node.id());
            if (p != null) {
                x[i] = p.x();
                y[i] = p.y();
                placed[i] = true;
            } else {
                isNew[i] = true;
                anyNew = true;
            }
        }
        boolean fresh = true;
        for (boolean p : placed) {
            fresh &= !p;
        }

        // Place: each new node beside its first placed neighbour, or outside what is there.
        for (int i = 0; i < n; i++) {
            if (placed[i]) {
                continue;
            }
            double angle = angle(nodes.get(i).id());
            int anchor = -1;
            for (int j : adjacent.get(i)) {
                if (placed[j]) {
                    anchor = j;
                    break;
                }
            }
            if (anchor >= 0) {
                double r = EDGE_LENGTH + (radius(w[i], h[i]) + radius(w[anchor], h[anchor]));
                x[i] = x[anchor] + r * Math.cos(angle);
                y[i] = y[anchor] + r * Math.sin(angle);
            } else {
                double cx = 0;
                double cy = 0;
                int count = 0;
                for (int j = 0; j < n; j++) {
                    if (placed[j]) {
                        cx += x[j];
                        cy += y[j];
                        count++;
                    }
                }
                if (count > 0) {
                    cx /= count;
                    cy /= count;
                    double spread = 0;
                    for (int j = 0; j < n; j++) {
                        if (placed[j]) {
                            spread = Math.max(spread, Math.hypot(x[j] - cx, y[j] - cy) + radius(w[j], h[j]));
                        }
                    }
                    double r = spread + EDGE_LENGTH / 2 + radius(w[i], h[i]);
                    x[i] = cx + r * Math.cos(angle);
                    y[i] = cy + r * Math.sin(angle);
                }
            }
            placed[i] = true;
        }

        if (anyNew && n > 1) {
            relax(nodes, x, y, isNew, edges, fresh ? EDGE_LENGTH : EDGE_LENGTH / 2);
        }
        separate(nodes, x, y, w, h, isNew);

        Map<String, Point> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            out.put(nodes.get(i).id(), new Point(x[i], y[i]));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The box around every node of {@code graph} that has a position, each drawn at its size. */
    public static Box fit(Map<String, Point> positions, MapGraph graph, Function<Node, Size> sizes) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Node node : graph.nodes()) {
            Point p = positions.get(node.id());
            if (p == null) {
                continue;
            }
            Size s = sizes.apply(node);
            minX = Math.min(minX, p.x() - s.width() / 2);
            maxX = Math.max(maxX, p.x() + s.width() / 2);
            minY = Math.min(minY, p.y() - s.height() / 2);
            maxY = Math.max(maxY, p.y() + s.height() / 2);
        }
        return minX > maxX ? new Box(0, 0, 0, 0) : new Box(minX, minY, maxX, maxY);
    }

    // ---- phases ----------------------------------------------------------------------------------------------------

    private static void relax(List<Node> nodes, double[] x, double[] y, boolean[] isNew,
                              List<int[]> edges,
                              double startStep) {
        int n = x.length;
        double k = EDGE_LENGTH;
        double[] dx = new double[n];
        double[] dy = new double[n];
        for (int round = 0; round < ITERATIONS; round++) {
            java.util.Arrays.fill(dx, 0);
            java.util.Arrays.fill(dy, 0);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (!isNew[i] && !isNew[j]) {
                        continue; // the old part was settled last time; only what the new nodes do counts
                    }
                    double ux = x[i] - x[j];
                    double uy = y[i] - y[j];
                    double d = Math.hypot(ux, uy);
                    if (d < 1e-6) {
                        double a = angle(nodes.get(i).id() + " " + nodes.get(j).id());
                        ux = Math.cos(a);
                        uy = Math.sin(a);
                        d = 1;
                    } else {
                        ux /= d;
                        uy /= d;
                    }
                    double f = k * k / Math.max(d, 1);
                    dx[i] += ux * f;
                    dy[i] += uy * f;
                    dx[j] -= ux * f;
                    dy[j] -= uy * f;
                }
            }
            for (int[] e : edges) {
                int a = e[0];
                int b = e[1];
                if (!isNew[a] && !isNew[b]) {
                    continue;
                }
                double ux = x[a] - x[b];
                double uy = y[a] - y[b];
                double d = Math.hypot(ux, uy);
                if (d < 1e-6) {
                    continue;
                }
                double f = d * d / k;
                dx[a] -= ux / d * f;
                dy[a] -= uy / d * f;
                dx[b] += ux / d * f;
                dy[b] += uy / d * f;
            }
            double cx = 0;
            double cy = 0;
            for (int i = 0; i < n; i++) {
                cx += x[i];
                cy += y[i];
            }
            cx /= n;
            cy /= n;
            double step = startStep * (1 - (double) round / ITERATIONS);
            for (int i = 0; i < n; i++) {
                if (isNew[i]) {
                    dx[i] += (cx - x[i]) * GRAVITY * k;
                    dy[i] += (cy - y[i]) * GRAVITY * k;
                }
                double len = Math.hypot(dx[i], dy[i]);
                if (isNew[i] && len > 1e-9) {
                    double move = Math.min(len, step);
                    x[i] += dx[i] / len * move;
                    y[i] += dy[i] / len * move;
                }
            }
        }
    }

    private static void separate(List<Node> nodes, double[] x, double[] y, double[] w, double[] h,
                                 boolean[] isNew) {
        int n = x.length;
        for (int pass = 0; pass < SEPARATION_PASSES; pass++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double ddx = x[j] - x[i];
                    double ddy = y[j] - y[i];
                    double ox = (w[i] + w[j]) / 2 + GAP - Math.abs(ddx);
                    double oy = (h[i] + h[j]) / 2 + GAP - Math.abs(ddy);
                    if (ox <= 0 || oy <= 0) {
                        continue;
                    }
                    moved = true;
                    double mi = isNew[i] ? 1 : OLD_WEIGHT;
                    double mj = isNew[j] ? 1 : OLD_WEIGHT;
                    double share = mi / (mi + mj);
                    if (ox < oy) {
                        double sign = ddx != 0 ? Math.signum(ddx) : tieSign(nodes, i, j);
                        double push = ox + 1e-6;
                        x[i] -= sign * push * share;
                        x[j] += sign * push * (1 - share);
                    } else {
                        double sign = ddy != 0 ? Math.signum(ddy) : tieSign(nodes, i, j);
                        double push = oy + 1e-6;
                        y[i] -= sign * push * share;
                        y[j] += sign * push * (1 - share);
                    }
                }
            }
            if (!moved) {
                return;
            }
        }
    }

    // ---- determinism -------------------------------------------------------------------------------------------------

    private static double radius(double w, double h) {
        return Math.max(w, h) / 2;
    }

    private static double tieSign(List<Node> nodes, int i, int j) {
        return (mix(nodes.get(i).id().hashCode() * 31L + nodes.get(j).id().hashCode()) & 1) == 0 ? 1 : -1;
    }

    /** An angle in [0, 2π) hashed from {@code key}: String.hashCode is specified, so this is stable across runs. */
    private static double angle(String key) {
        long bits = mix(key.hashCode()) >>> 11;
        return bits * 0x1.0p-53 * 2 * Math.PI;
    }

    /** SplitMix64's finaliser over a seeded input. */
    private static long mix(long v) {
        long z = v + SEED + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
