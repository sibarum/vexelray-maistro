package dev.vexelray.maistro.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How a reply carries changes to the mind map: a fenced block tagged {@value #TAG} at the end of the reply, one
 * statement per line. This class holds the instruction given to the model, the tolerant parser, and the reverse
 * ({@link #render(MapGraph)}) for handing a map back to the model.
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * block     = fence "maistro-map" NL { line NL } fence        (fence: three or more ` or ~; unclosed runs to the end)
 * line      = node | edge | focus | carve | comment | blank
 * node      = "node" id [ string ] { kind | number }           kind: concept | file | app | question; number: 0..1 surprise
 * edge      = "edge" id [ "->" ] id [ string ]
 * focus     = "focus" id
 * carve     = "carve" id [ string ]                              (the string is the reason)
 * comment   = "#" anything
 * id        = one or more characters, no whitespace, no double quote
 * string    = '"' { char | '\"' | '\\' } '"'
 * </pre>
 *
 * <p>Words are case-insensitive; ids and strings are not. A line that is not understood is skipped and reported as a
 * {@link MapDelta.Problem}; a line understood in part (an unknown kind, a surprise out of range) is applied with the
 * part that was understood, and reported too. Nothing here throws on bad input.
 */
public final class MapProtocol {

    /** The info string that marks a block as the map's. */
    public static final String TAG = "maistro-map";

    /** Appended to the agent's system prompt: tells the model how and when to emit a map block. */
    public static final String INSTRUCTIONS = """
            Maistro shows this conversation as a mind map of concepts. At the end of a reply that introduces, links or \
            retires ideas, add one fenced code block tagged maistro-map (```maistro-map ... ```), one statement per line: \
            `node <id> "<label>" [concept|file|app|question] [surprise 0..1]` adds or updates a node; \
            `edge <from> <to> ["<label>"]` links two nodes; `focus <id>` sets the node the reply is mainly about; \
            `carve <id> "<reason>"` removes a node that turned out wrong or irrelevant. Ids are short, stable, lowercase \
            and hyphenated (ring-buffer, src/Main.java); reuse the same id whenever you mean the same thing, because a \
            repeated node is merged, not duplicated. Emit only what this reply changes, usually two to six lines, not \
            the whole map. Give surprise only when something contradicted what you expected (1 is a complete \
            surprise). For an ambiguous request, answer the likeliest reading and add the other readings as question \
            nodes. Do not mention the block in your prose; Maistro strips it from the transcript. Omit the block when \
            nothing changed.""";

    private MapProtocol() {
    }

    /**
     * A reply split into what the transcript shows and what the map takes.
     *
     * @param prose the reply with every map block removed (and the blank lines it leaves collapsed)
     * @param delta every map block's statements, in order, merged into one delta
     */
    public record Parsed(String prose, MapDelta delta) {
    }

    /** Splits {@code reply} into prose and delta. A reply with no block is all prose and an empty delta. */
    public static Parsed parse(String reply) {
        if (reply == null || reply.isEmpty()) {
            return new Parsed("", MapDelta.EMPTY);
        }
        String[] lines = reply.split("\r?\n", -1);
        StringBuilder prose = new StringBuilder();
        Collector c = new Collector();
        String closing = null;
        int openedAt = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (closing == null) {
                Optional<String> fence = opening(line);
                if (fence.isPresent()) {
                    closing = fence.get();
                    openedAt = i + 1;
                } else {
                    prose.append(line).append('\n');
                }
            } else if (closes(line, closing)) {
                closing = null;
            } else {
                c.statement(i + 1, line);
            }
        }
        if (closing != null) {
            c.problems.add(new MapDelta.Problem(openedAt, lines[openedAt - 1],
                    "map block never closed; read to the end of the reply"));
        }
        return new Parsed(tidy(prose), c.delta());
    }

    /** {@code graph} as one map block: what to give the model when a turn is forked from part of the map. */
    public static String render(MapGraph graph) {
        StringBuilder out = new StringBuilder("```").append(TAG).append('\n');
        for (Node n : graph.nodes()) {
            out.append("node ").append(n.id()).append(' ').append(quote(n.label())).append(' ').append(n.kind().word());
            if (n.surprise() > 0) {
                out.append(' ').append(n.surprise());
            }
            out.append('\n');
        }
        for (Edge e : graph.edges()) {
            out.append("edge ").append(e.from()).append(' ').append(e.to());
            if (!e.label().isEmpty()) {
                out.append(' ').append(quote(e.label()));
            }
            out.append('\n');
        }
        for (Carve c : graph.carves()) {
            out.append("carve ").append(c.id()).append(' ').append(quote(c.reason())).append('\n');
        }
        graph.focus().ifPresent(f -> out.append("focus ").append(f).append('\n'));
        return out.append("```\n").toString();
    }

    // ---- fences ----------------------------------------------------------------------------------------------------

    /** The fence that would close a map block opened by {@code line}, if it opens one. */
    private static Optional<String> opening(String line) {
        String t = line.strip();
        int n = fenceLength(t);
        if (n == 0) {
            return Optional.empty();
        }
        String info = t.substring(n).strip();
        int space = info.indexOf(' ');
        String word = space < 0 ? info : info.substring(0, space);
        return word.equalsIgnoreCase(TAG) ? Optional.of(t.substring(0, n)) : Optional.empty();
    }

    private static boolean closes(String line, String fence) {
        String t = line.strip();
        int n = fenceLength(t);
        return n >= fence.length() && n == t.length() && t.charAt(0) == fence.charAt(0);
    }

    private static int fenceLength(String t) {
        if (t.isEmpty() || (t.charAt(0) != '`' && t.charAt(0) != '~')) {
            return 0;
        }
        int n = 0;
        while (n < t.length() && t.charAt(n) == t.charAt(0)) {
            n++;
        }
        return n >= 3 ? n : 0;
    }

    /** Trailing whitespace off, and no run of more than one blank line where a block was taken out. */
    private static String tidy(StringBuilder prose) {
        String[] lines = prose.toString().split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blanks = 0;
        for (String l : lines) {
            if (l.isBlank()) {
                blanks++;
                continue;
            }
            if (!out.isEmpty()) {
                out.append(blanks > 0 ? "\n\n" : "\n");
            }
            blanks = 0;
            out.append(l.stripTrailing());
        }
        return out.toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ---- statements ------------------------------------------------------------------------------------------------

    /** One reply's worth of statements, accumulated across blocks. */
    private static final class Collector {
        final List<MapDelta.NodeSpec> nodes = new ArrayList<>();
        final List<MapDelta.EdgeSpec> edges = new ArrayList<>();
        final List<MapDelta.CarveSpec> carves = new ArrayList<>();
        final List<MapDelta.Problem> problems = new ArrayList<>();
        String focus;

        MapDelta delta() {
            return new MapDelta(nodes, edges, carves, focus, problems);
        }

        void statement(int number, String line) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                return;
            }
            Tokens tokens = Tokens.of(t);
            if (tokens.unterminated) {
                problem(number, line, "unterminated string; line skipped");
                return;
            }
            List<Token> ts = tokens.list;
            String verb = ts.getFirst().quoted ? "" : ts.getFirst().text.toLowerCase(java.util.Locale.ROOT);
            List<Token> args = ts.subList(1, ts.size());
            if (verb.equals("node")) {
                node(number, line, args);
            } else if (verb.equals("edge")) {
                edge(number, line, args);
            } else if (verb.equals("focus")) {
                focus(number, line, args);
            } else if (verb.equals("carve")) {
                carve(number, line, args);
            } else {
                problem(number, line, "unknown statement; expected node, edge, focus or carve");
            }
        }

        private void node(int number, String line, List<Token> args) {
            if (args.isEmpty() || !args.getFirst().isId()) {
                problem(number, line, "node needs an id");
                return;
            }
            String id = args.getFirst().text;
            int i = 1;
            String label = "";
            if (i < args.size() && args.get(i).quoted) {
                label = args.get(i++).text;
            }
            Kind kind = Kind.CONCEPT;
            double surprise = 0;
            List<String> notes = new ArrayList<>();
            for (; i < args.size(); i++) {
                Token a = args.get(i);
                Optional<Double> num = a.quoted ? Optional.empty() : number(a.text);
                Optional<Kind> k = a.quoted ? Optional.empty() : Kind.parse(a.text);
                if (k.isPresent()) {
                    kind = k.get();
                } else if (num.isPresent()) {
                    double v = num.get();
                    if (v < 0 || v > 1) {
                        notes.add("surprise " + a.text + " clamped to 0..1");
                    }
                    surprise = v;
                } else {
                    notes.add("ignored \"" + a.text + "\"");
                }
            }
            nodes.add(new MapDelta.NodeSpec(id, label, kind, surprise));
            if (!notes.isEmpty()) {
                problem(number, line, "node " + id + " taken; " + String.join(", ", notes));
            }
        }

        private void edge(int number, String line, List<Token> args) {
            List<Token> a = new ArrayList<>(args);
            if (a.size() >= 2 && !a.get(1).quoted && a.get(1).text.equals("->")) {
                a.remove(1);
            }
            if (a.size() < 2 || !a.get(0).isId() || !a.get(1).isId()) {
                problem(number, line, "edge needs two ids");
                return;
            }
            String label = "";
            int extra = a.size() - 2;
            if (extra >= 1 && a.get(2).quoted) {
                label = a.get(2).text;
                extra--;
            }
            edges.add(new MapDelta.EdgeSpec(a.get(0).text, a.get(1).text, label));
            if (extra > 0) {
                problem(number, line, "edge taken; extra words ignored");
            }
        }

        private void focus(int number, String line, List<Token> args) {
            if (args.size() != 1 || !args.getFirst().isId()) {
                problem(number, line, "focus needs exactly one id");
                return;
            }
            focus = args.getFirst().text;
        }

        private void carve(int number, String line, List<Token> args) {
            if (args.isEmpty() || !args.getFirst().isId()) {
                problem(number, line, "carve needs an id");
                return;
            }
            boolean hasReason = args.size() > 1 && args.get(1).quoted;
            String reason = hasReason ? args.get(1).text : "";
            carves.add(new MapDelta.CarveSpec(args.getFirst().text, reason));
            if (args.size() > (hasReason ? 2 : 1)) {
                problem(number, line, "carve taken; extra words ignored");
            }
        }

        private void problem(int number, String line, String reason) {
            problems.add(new MapDelta.Problem(number, line, reason));
        }

        private static Optional<Double> number(String s) {
            if (s.isEmpty() || !(Character.isDigit(s.charAt(0)) || s.charAt(0) == '.' || s.charAt(0) == '-')) {
                return Optional.empty();
            }
            try {
                double v = Double.parseDouble(s);
                return Double.isFinite(v) ? Optional.of(v) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    private record Token(String text, boolean quoted) {
        boolean isId() {
            return !quoted && Ids.valid(text);
        }
    }

    /** A line cut into words and double-quoted strings. */
    private static final class Tokens {
        final List<Token> list = new ArrayList<>();
        boolean unterminated;

        static Tokens of(String line) {
            Tokens out = new Tokens();
            int i = 0;
            int n = line.length();
            while (i < n) {
                char c = line.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '"') {
                    StringBuilder s = new StringBuilder();
                    i++;
                    boolean closed = false;
                    while (i < n) {
                        char d = line.charAt(i++);
                        if (d == '\\' && i < n) {
                            s.append(line.charAt(i++));
                        } else if (d == '"') {
                            closed = true;
                            break;
                        } else {
                            s.append(d);
                        }
                    }
                    if (!closed) {
                        out.unterminated = true;
                    }
                    out.list.add(new Token(s.toString(), true));
                } else {
                    int start = i;
                    while (i < n && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != '"') {
                        i++;
                    }
                    out.list.add(new Token(line.substring(start, i), false));
                }
            }
            return out;
        }
    }
}
