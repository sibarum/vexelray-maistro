package dev.vexelray.maistro.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vexelray.gui.automation.cli.Reply;
import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppToolsTest {

    /** The smallest file {@link AppTools#complete} takes for a PNG: the signature, then an IEND chunk. */
    static final byte[] PNG = png();

    static final Apps.App CALC = new Apps.App("calculator", "Calculator", 11, 6001, "2026-10-25T10:00:00Z", "C:\\calc");
    static final Apps.App VEX = new Apps.App("vex", "Vex", 22, 6002, "2026-10-25T10:01:00Z", "C:\\vex");
    static final Apps.App VEX2 = new Apps.App("vex", "Vex", 33, 6003, "2026-10-25T10:02:00Z", "C:\\vex");

    /** Applications that answer each command with {@code answer}, and remember what they were sent. */
    static final class FakeApps implements Apps {
        final List<App> running;
        final List<String> sent = new ArrayList<>();
        BiFunction<App, String, String> answer = (app, command) -> "ok " + command;
        App reached;

        FakeApps(App... running) {
            this.running = List.of(running);
        }

        @Override
        public List<App> running() {
            return running;
        }

        @Override
        public List<Reply> send(App app, List<String> commands) {
            reached = app;
            List<Reply> out = new ArrayList<>();
            for (String c : commands) {
                sent.add(c);
                Reply r = new Reply(c, answer.apply(app, c).lines().toList());
                out.add(r);
                if (r.err()) {
                    break;
                }
            }
            return out;
        }
    }

    @TempDir
    Path dir;

    private Map<String, Tool> tools(Apps apps) {
        Map<String, Tool> out = new java.util.LinkedHashMap<>();
        for (Tool t : new AppTools(apps, dir.resolve("shots")).tools()) {
            out.put(t.name(), t);
        }
        return out;
    }

    private static String text(Tool.Result r) {
        return r.content().getFirst().get("text").asString();
    }

    @Test
    void fourToolsWithObjectSchemas() {
        Map<String, Tool> t = tools(new FakeApps());
        assertEquals(List.of("apps", "tree", "find", "shot"), List.copyOf(t.keySet()));
        for (Tool tool : t.values()) {
            assertEquals("object", tool.inputSchema().get("type").asString(), tool.name());
            assertFalse(tool.description().isBlank());
        }
        assertEquals("[\"text\"]", t.get("find").inputSchema().get("required").toJson());
        assertTrue(t.get("tree").inputSchema().get("properties").has("app"));
    }

    @Test
    void appsListsWhatIsRunning() {
        Tool.Result r = tools(new FakeApps(CALC, VEX)).get("apps").call(JsonValue.Obj.EMPTY);
        assertFalse(r.isError());
        assertEquals(List.of("app\tpid\ttitle\tstarted\tcwd",
                "calculator\t11\tCalculator\t2026-10-25T10:00:00Z\tC:\\calc",
                "vex\t22\tVex\t2026-10-25T10:01:00Z\tC:\\vex"), text(r).lines().toList());

        Tool.Result none = tools(new FakeApps()).get("apps").call(JsonValue.Obj.EMPTY);
        assertFalse(none.isError());
        assertTrue(text(none).contains("--automation=0"));
    }

    @Test
    void theOneRunningApplicationNeedsNoName() {
        FakeApps apps = new FakeApps(CALC);
        apps.answer = (app, c) -> "ok v1\n18 textfield \"\" @entry [22,482 659x80]";
        Tool.Result r = tools(apps).get("tree").call(JsonValue.Obj.EMPTY);
        assertFalse(r.isError());
        assertEquals(CALC, apps.reached);
        assertEquals(List.of("tree"), apps.sent);
        assertEquals("ok v1\n18 textfield \"\" @entry [22,482 659x80]", text(r));
    }

    @Test
    void anApplicationIsPickedByNameOrPid() {
        FakeApps apps = new FakeApps(CALC, VEX);
        tools(apps).get("tree").call(Json.parse("{\"app\":\"VEX\"}"));
        assertEquals(VEX, apps.reached);
        tools(apps).get("tree").call(Json.parse("{\"app\":\"11\"}"));
        assertEquals(CALC, apps.reached);
    }

    @Test
    void anAmbiguousOrMissingApplicationIsSaidNotGuessed() {
        Map<String, Tool> t = tools(new FakeApps(CALC, VEX, VEX2));
        Tool.Result several = t.get("tree").call(JsonValue.Obj.EMPTY);
        assertTrue(several.isError());
        assertTrue(text(several).contains("calculator (pid 11), vex (pid 22), vex (pid 33)"), text(several));

        Tool.Result twoVex = t.get("tree").call(Json.parse("{\"app\":\"vex\"}"));
        assertTrue(twoVex.isError());
        assertTrue(text(twoVex).contains("give its pid"), text(twoVex));

        Tool.Result nothing = t.get("tree").call(Json.parse("{\"app\":\"paint\"}"));
        assertTrue(nothing.isError());
        assertTrue(text(nothing).startsWith("No running application is called paint"), text(nothing));

        assertTrue(tools(new FakeApps()).get("shot").call(JsonValue.Obj.EMPTY).isError());
    }

    @Test
    void aWindowIsChosenFirstAndItsRefusalIsTheAnswer() {
        FakeApps apps = new FakeApps(CALC);
        tools(apps).get("find").call(Json.parse("{\"window\":\"settings\",\"text\":\" entry \"}"));
        assertEquals(List.of("window settings", "find entry"), apps.sent);

        apps.sent.clear();
        apps.answer = (app, c) -> c.startsWith("window") ? "err no window 'x'" : "ok";
        Tool.Result r = tools(apps).get("tree").call(Json.parse("{\"window\":\"x\"}"));
        assertTrue(r.isError());
        assertEquals("err no window 'x'", text(r));
        assertEquals(List.of("window x"), apps.sent);
    }

    @Test
    void shotReturnsThePictureAndLeavesNoFile() {
        FakeApps apps = new FakeApps(CALC);
        apps.answer = (app, c) -> {
            Path target = Path.of(c.substring("shot ".length()));
            try {
                Files.write(target, PNG);
            } catch (IOException e) {
                return "err " + e;
            }
            return "ok " + target + " " + PNG.length + " bytes";
        };
        Tool.Result r = tools(apps).get("shot").call(JsonValue.Obj.EMPTY);
        assertFalse(r.isError(), r.toString());
        assertEquals(2, r.content().size());
        assertTrue(text(r).startsWith("calculator: ok "), text(r));
        JsonValue image = r.content().get(1);
        assertEquals("image", image.get("type").asString());
        assertEquals("image/png", image.get("mimeType").asString());
        assertArrayEquals(PNG, Base64.getDecoder().decode(image.get("data").asString()));

        String sentPath = apps.sent.getFirst().substring("shot ".length());
        assertFalse(sentPath.contains("\\"), sentPath);
        assertTrue(sentPath.startsWith(dir.resolve("shots").toAbsolutePath().toString().replace('\\', '/')));
        assertFalse(Files.exists(Path.of(sentPath)));
    }

    @Test
    void aShotThatNeverCompletesIsAnError() {
        FakeApps apps = new FakeApps(CALC);
        apps.answer = (app, c) -> {
            try {
                Files.write(Path.of(c.substring("shot ".length())), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            } catch (IOException e) {
                return "err " + e;
            }
            return "ok written";
        };
        Tool.Result r = tools(apps).get("shot").call(JsonValue.Obj.EMPTY);
        assertTrue(r.isError());
        assertTrue(text(r).contains("did not become a complete PNG"), text(r));
    }

    @Test
    void anApplicationThatCannotBeReachedIsSaid() {
        Apps gone = new Apps() {
            @Override
            public List<App> running() {
                return List.of(CALC);
            }

            @Override
            public List<Reply> send(App app, List<String> commands) throws IOException {
                throw new IOException("nothing is listening on localhost:6001");
            }
        };
        Tool.Result r = tools(gone).get("tree").call(JsonValue.Obj.EMPTY);
        assertTrue(r.isError());
        assertEquals("Could not reach calculator (pid 11, port 6001): nothing is listening on localhost:6001",
                text(r));
    }

    private static byte[] png() {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        byte[] iend = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82};
        byte[] out = new byte[sig.length + iend.length];
        System.arraycopy(sig, 0, out, 0, sig.length);
        System.arraycopy(iend, 0, out, sig.length, iend.length);
        return out;
    }

    @Test
    void theFixturePngIsWhatCompleteLooksFor() {
        assertEquals("IEND", new String(PNG, PNG.length - 8, 4, StandardCharsets.US_ASCII));
    }
}
