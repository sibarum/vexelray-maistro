package dev.vexelray.maistro.archive;

import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonException;
import dev.vexelray.maistro.agent.JsonValue;
import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.map.Carve;
import dev.vexelray.maistro.map.Edge;
import dev.vexelray.maistro.map.Kind;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;
import dev.vexelray.maistro.map.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Conversation} as JSON and back, written out field by field: nothing reflects, so the native image needs no
 * metadata for it.
 *
 * <h2>Reading is forgiving, within reason</h2>
 *
 * <p>A file is the application's own, but it may be from an older build or cut short by a crash. A missing field
 * reads as its default, an edge or node that does not hold together is skipped, and only a file that is not JSON
 * or has no id is refused, with an {@link IllegalArgumentException} saying why. Nothing comes back live: a turn
 * saved mid-stream is finished, and an ask nobody answered has lapsed, because the process it waited on is gone.
 */
public final class ConversationCodec {

    /** Written into every file, so a later build can tell which shape it is reading. */
    public static final int FORMAT = 1;

    private ConversationCodec() {
    }

    public static String encode(Conversation c) {
        JsonValue.Obj.Builder agent = JsonValue.Obj.builder()
                .put("sessionId", c.agent().sessionId())
                .put("model", c.agent().model())
                .put("cwd", c.agent().cwd())
                .put("costUsd", c.agent().costUsd())
                .put("turns", c.agent().turns())
                .put("inputTokens", c.agent().inputTokens())
                .put("outputTokens", c.agent().outputTokens());
        return JsonValue.Obj.builder()
                .put("format", FORMAT)
                .put("id", c.id())
                .put("title", c.title())
                .put("started", c.started())
                .put("saved", c.saved())
                .put("agent", agent.build())
                .put("transcript", transcript(c.transcript()))
                .put("map", map(c.graph(), c.positions(), c.mindTurn()))
                .build()
                .toJson();
    }

    /** @throws IllegalArgumentException if {@code json} is not JSON, or names no conversation id */
    public static Conversation decode(String json) {
        JsonValue v;
        try {
            v = Json.parse(json);
        } catch (JsonException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
        String id = v.get("id").asString();
        if (id.isBlank()) {
            throw new IllegalArgumentException("no conversation id");
        }
        JsonValue a = v.get("agent");
        Conversation.Agent agent = new Conversation.Agent(a.get("sessionId").asString(), a.get("model").asString(),
                a.get("cwd").asString(), a.get("costUsd").asDouble(0), (int) a.get("turns").asLong(0),
                a.get("inputTokens").asLong(0), a.get("outputTokens").asLong(0));
        JsonValue m = v.get("map");
        return new Conversation(id, v.get("title").asString(), v.get("started").asLong(0), v.get("saved").asLong(0),
                agent, transcript(v.get("transcript")), graph(m), positions(m.get("positions")),
                (int) m.get("turn").asLong(0));
    }

    // ---- the transcript -----------------------------------------------------------------------------------------

    private static JsonValue transcript(Transcript t) {
        List<JsonValue> turns = new ArrayList<>(t.size());
        for (Turn turn : t.turns()) {
            turns.add(JsonValue.Obj.builder()
                    .put("id", turn.id())
                    .put("who", turn.who().name())
                    .put("title", turn.title())
                    .put("text", turn.text())
                    .put("failed", turn.failed())
                    .put("chips", JsonValue.Arr.ofStrings(turn.chips()))
                    .put("ask", turn.ask().name())
                    .build());
        }
        return JsonValue.Obj.builder()
                .put("nextId", t.nextId())
                .put("turns", new JsonValue.Arr(turns))
                .build();
    }

    private static Transcript transcript(JsonValue v) {
        List<Turn> turns = new ArrayList<>();
        long highest = 0;
        for (JsonValue t : v.get("turns").items()) {
            long id = t.get("id").asLong(0);
            List<String> chips = new ArrayList<>();
            for (JsonValue c : t.get("chips").items()) {
                chips.add(c.asString());
            }
            Turn.Ask ask = ask(t.get("ask").asString());
            turns.add(new Turn(id, who(t.get("who").asString()), t.get("title").asString(), t.get("text").asString(),
                    false, t.get("failed").asBool(false), chips, ask == Turn.Ask.PENDING ? Turn.Ask.LAPSED : ask,
                    List.of()));
            highest = Math.max(highest, id);
        }
        // Never hand out an id a saved turn has, whatever the file says.
        return new Transcript(turns, Math.max(v.get("nextId").asLong(1), highest + 1));
    }

    private static Turn.Who who(String name) {
        for (Turn.Who w : Turn.Who.values()) {
            if (w.name().equals(name)) {
                return w;
            }
        }
        return Turn.Who.NOTE;
    }

    private static Turn.Ask ask(String name) {
        for (Turn.Ask a : Turn.Ask.values()) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return Turn.Ask.NONE;
    }

    // ---- the map --------------------------------------------------------------------------------------------------

    private static JsonValue map(MapGraph g, Map<String, Layout.Point> positions, int turn) {
        List<JsonValue> nodes = new ArrayList<>();
        for (Node n : g.nodes()) {
            nodes.add(JsonValue.Obj.builder()
                    .put("id", n.id())
                    .put("label", n.label())
                    .put("kind", n.kind().word())
                    .put("turn", n.turn())
                    .put("surprise", n.surprise())
                    .build());
        }
        List<JsonValue> edges = new ArrayList<>();
        for (Edge e : g.edges()) {
            edges.add(JsonValue.Obj.builder()
                    .put("from", e.from())
                    .put("to", e.to())
                    .put("label", e.label())
                    .put("turn", e.turn())
                    .build());
        }
        List<JsonValue> carves = new ArrayList<>();
        for (Carve c : g.carves()) {
            carves.add(JsonValue.Obj.builder()
                    .put("id", c.id())
                    .put("reason", c.reason())
                    .put("turn", c.turn())
                    .build());
        }
        JsonValue.Obj.Builder at = JsonValue.Obj.builder();
        positions.forEach((id, p) -> at.put(id, JsonValue.Arr.of(JsonValue.Num.of(p.x()), JsonValue.Num.of(p.y()))));
        return JsonValue.Obj.builder()
                .put("turn", turn)
                .put("focus", g.focus().orElse(""))
                .put("nodes", new JsonValue.Arr(nodes))
                .put("edges", new JsonValue.Arr(edges))
                .put("carves", new JsonValue.Arr(carves))
                .put("positions", at.build())
                .build();
    }

    private static MapGraph graph(JsonValue m) {
        MapGraph g = MapGraph.EMPTY;
        for (JsonValue n : m.get("nodes").items()) {
            try {
                g = g.withNode(new Node(n.get("id").asString(), n.get("label").asString(),
                        Kind.parse(n.get("kind").asString()).orElse(Kind.CONCEPT), (int) n.get("turn").asLong(0),
                        n.get("surprise").asDouble(0)));
            } catch (IllegalArgumentException skipped) {
                // a node whose id is not one; the rest of the map still loads
            }
        }
        for (JsonValue e : m.get("edges").items()) {
            try {
                g = g.withEdge(new Edge(e.get("from").asString(), e.get("to").asString(), e.get("label").asString(),
                        (int) e.get("turn").asLong(0)));
            } catch (IllegalArgumentException skipped) {
                // an edge whose ends are not ids
            }
        }
        for (JsonValue c : m.get("carves").items()) {
            try {
                g = g.carve(c.get("id").asString(), c.get("reason").asString(), (int) c.get("turn").asLong(0));
            } catch (IllegalArgumentException skipped) {
                // a carve whose id is not one
            }
        }
        String focus = m.get("focus").asString();
        return focus.isEmpty() ? g : g.focus(focus);
    }

    private static Map<String, Layout.Point> positions(JsonValue v) {
        Map<String, Layout.Point> out = new LinkedHashMap<>();
        v.members().forEach((id, p) -> {
            if (p.size() == 2) {
                out.put(id, new Layout.Point(p.get(0).asDouble(0), p.get(1).asDouble(0)));
            }
        });
        return out;
    }
}
