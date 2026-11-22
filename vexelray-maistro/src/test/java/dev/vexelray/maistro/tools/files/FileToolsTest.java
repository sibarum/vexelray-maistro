package dev.vexelray.maistro.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;
import dev.vexelray.maistro.spi.FileNames;
import dev.vexelray.maistro.tools.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolsTest {

    /** Takes every name as it is, so these tests are the tools', not an OS's naming rules. */
    static final FileNames ANY = new FileNames() {
        @Override
        public String separators(String given) {
            return given;
        }

        @Override
        public String problem(String path) {
            return "";
        }
    };

    @TempDir
    Path work;

    private FileTools tools;

    private static final String ODD_NAME = "it's ünïcode & spaces.md";

    @BeforeEach
    void make() throws Exception {
        tools = new FileTools(new Paths(ANY, work.toAbsolutePath()));
        Files.createDirectories(work.resolve(".git"));
        write(".gitignore", "build/\n*.log\n");
        write("plain.txt", "a\nb\nc\n");
        write("crlf.txt", "one\r\ntwo\r\n");
        write("mixed.txt", "a\r\nb\nc\rd");
        Files.write(work.resolve("bom.txt"), Codec.encode("bom\n", Codec.Encoding.UTF_8_BOM));
        Files.write(work.resolve("utf16.txt"), Codec.encode("héllo\r\n", Codec.Encoding.UTF_16LE_BOM));
        Files.write(work.resolve("latin.txt"), "café\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(work.resolve("bin.dat"), new byte[]{1, 0, 2, 0, 3});
        write(ODD_NAME, "ü😀x marks\n");
        write("src/Main.java", "class Main {}\n// TODO: tidy (soon)\n");
        write("src/notes.md", "todo later\n");
        write("build/out.class", "compiled");
        write("debug.log", "TODO in a log\n");
        write("deep/a/b/c/d.txt", "deep\n");
    }

    private void write(String rel, String text) throws Exception {
        Path p = work.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    private Tool tool(String name) {
        return tools.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** Calls {@code name} with {@code json}; a success is held to the tool's output schema. */
    private Tool.Result call(String name, String json) {
        Tool t = tool(name);
        Tool.Result r = t.call(Json.parse(json));
        if (!r.isError()) {
            assertTrue(r.structured().isObject(), name + " gave no structured result");
            assertEquals(List.of(), SchemaCheck.problems(t.outputSchema(), r.structured()), name + ": " + json);
        }
        return r;
    }

    private JsonValue ok(String name, String json) {
        Tool.Result r = call(name, json);
        assertFalse(r.isError(), () -> name + " " + json + " failed: " + text(r));
        return r.structured();
    }

    private String failed(String name, String json) {
        Tool.Result r = call(name, json);
        assertTrue(r.isError(), name + " " + json + " should have failed");
        return text(r);
    }

    private static String text(Tool.Result r) {
        return r.content().getFirst().get("text").asString();
    }

    @Test
    void everyToolOnlyReadsAndDeclaresBothSchemas() {
        assertEquals(List.of("stat", "list", "tree", "read", "search"), tools.tools().stream().map(Tool::name).toList());
        for (Tool t : tools.tools()) {
            assertTrue(t.readOnly(), t.name());
            assertEquals("object", t.inputSchema().get("type").asString());
            assertEquals("object", t.outputSchema().get("type").asString());
        }
    }

    // ---------------------------------------------------------------------------------------------- stat

    @Test
    void statSaysTheEncodingTheEndingsAndTheHash() throws Exception {
        JsonValue e = ok("stat", "{\"path\":\"crlf.txt\"}").get("entries").get(0);
        assertEquals("crlf.txt", e.get("path").asString());
        assertEquals("file", e.get("kind").asString());
        assertEquals("utf-8", e.get("encoding").asString());
        assertEquals("crlf", e.get("endings").get("style").asString());
        assertEquals(2, e.get("endings").get("crlf").asLong(0));
        assertEquals(2, e.get("lines").asLong(0));
        assertEquals(Codec.sha256(Files.readAllBytes(work.resolve("crlf.txt"))), e.get("sha256").asString());
    }

    @Test
    void statDescribesSeveralAndSaysWhatIsMissing() {
        JsonValue es = ok("stat", "{\"paths\":[\"bom.txt\",\"utf16.txt\",\"latin.txt\",\"src\",\"nope.txt\"]}")
                .get("entries");
        assertEquals("utf-8-bom", es.get(0).get("encoding").asString());
        assertEquals("utf-16le-bom", es.get(1).get("encoding").asString());
        assertEquals("unknown", es.get(2).get("encoding").asString(), "Latin-1 is not guessed");
        assertEquals("dir", es.get(3).get("kind").asString());
        assertEquals("missing", es.get(4).get("kind").asString());
    }

    @Test
    void statTakesOnePathOrSeveralNotBoth() {
        assertTrue(failed("stat", "{\"path\":\"a\",\"paths\":[\"b\"]}").contains("not both"));
        assertTrue(failed("stat", "{}").contains("'path' is required"));
    }

    // ---------------------------------------------------------------------------------------------- read

    @Test
    void readReturnsTheTextExactlyWithItsEndings() throws Exception {
        JsonValue r = ok("read", "{\"path\":\"mixed.txt\"}");
        assertEquals("a\r\nb\nc\rd", r.get("text").asString());
        assertEquals("mixed", r.get("endings").get("style").asString());
        assertEquals(4, r.get("lines").asLong(0));
        assertEquals(Codec.sha256(Files.readAllBytes(work.resolve("mixed.txt"))), r.get("sha256").asString());
        assertFalse(r.get("truncated").asBool(true));
    }

    @Test
    void readTakesALineRangeAndCanNumberIt() {
        JsonValue r = ok("read", "{\"path\":\"mixed.txt\",\"start_line\":2,\"end_line\":3}");
        assertEquals("b\nc\r", r.get("text").asString());
        assertEquals(2, r.get("start_line").asLong(0));
        assertEquals(3, r.get("end_line").asLong(0));
        assertEquals("2\tb\n3\tc\n", ok("read", "{\"path\":\"plain.txt\",\"start_line\":2,\"numbered\":true}")
                .get("text").asString());
        assertTrue(failed("read", "{\"path\":\"plain.txt\",\"start_line\":9}").contains("past the last line"));
        assertTrue(failed("read", "{\"path\":\"plain.txt\",\"start_line\":3,\"end_line\":2}").contains("before"));
    }

    @Test
    void readDecodesWhatIsMarkedAndOnlyWhatIsNamedOtherwise() {
        assertEquals("héllo\r\n", ok("read", "{\"path\":\"utf16.txt\"}").get("text").asString());
        assertEquals("bom\n", ok("read", "{\"path\":\"bom.txt\"}").get("text").asString(), "the mark is not text");
        String notText = failed("read", "{\"path\":\"latin.txt\"}");
        assertTrue(notText.contains("not detectably text") && notText.contains("latin-1"), notText);
        assertEquals("café\n", ok("read", "{\"path\":\"latin.txt\",\"encoding\":\"latin-1\"}").get("text")
                .asString());
        String bad = failed("read", "{\"path\":\"latin.txt\",\"encoding\":\"utf-8\"}");
        assertTrue(bad.contains("byte 3"), "a bad byte is named by its offset, never replaced: " + bad);
    }

    @Test
    void readAsBytesIsBase64ByRange() {
        JsonValue r = ok("read", "{\"path\":\"bin.dat\",\"as\":\"bytes\",\"offset\":1,\"length\":3}");
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{0, 2, 0}), r.get("base64").asString());
        assertTrue(r.get("truncated").asBool(false));
        assertEquals(4, r.get("next_offset").asLong(0));
        assertTrue(failed("read", "{\"path\":\"bin.dat\",\"as\":\"bytes\",\"start_line\":1}")
                .contains("offset and length"));
    }

    @Test
    void readRefusesWhatItCannotDoAndSaysWhatWould() {
        assertTrue(failed("read", "{\"path\":\"src\"}").contains("is a folder; list or tree it"));
        assertTrue(failed("read", "{\"path\":\"nope\"}").contains("not there"));
        String unknown = failed("read", "{\"path\":\"plain.txt\",\"lines\":3}");
        assertTrue(unknown.contains("'lines' is not an argument of read"), unknown);
        assertTrue(failed("read", "{\"path\":\"plain.txt\",\"start_line\":\"2\"}").contains("whole number"));
        assertTrue(failed("read", "{\"path\":\"plain.txt\",\"encoding\":\"cp1252\"}").contains("utf-8, utf-8-bom"));
    }

    @Test
    void anOddNameIsJustAName() {
        JsonValue r = ok("read", "{\"path\":" + Json.quote(ODD_NAME) + "}");
        assertEquals(ODD_NAME, r.get("path").asString());
        assertEquals("ü😀x marks\n", r.get("text").asString());
    }

    // ---------------------------------------------------------------------------------------------- list

    @Test
    void listIsOneFolderSortedWithWhatGitIgnores() {
        JsonValue r = ok("list", "{}");
        List<String> names = r.get("entries").items().stream().map(e -> e.get("name").asString()).toList();
        assertEquals(names.stream().sorted().toList(), names);
        assertTrue(names.containsAll(List.of("build", "debug.log", "src", "plain.txt")));
        JsonValue build = r.get("entries").items().stream().filter(e -> e.get("name").asString().equals("build"))
                .findFirst().orElseThrow();
        assertTrue(build.get("ignored").asBool(false));
        assertEquals("dir", build.get("kind").asString());
        JsonValue txt = ok("list", "{\"glob\":\"*.txt\",\"limit\":2}");
        assertEquals(6, txt.get("total").asLong(0));
        assertEquals(2, txt.get("entries").size());
        assertTrue(txt.get("truncated").asBool(false));
        assertTrue(failed("list", "{\"path\":\"plain.txt\"}").contains("not a folder"));
    }

    // ---------------------------------------------------------------------------------------------- tree

    @Test
    void treeSkipsWhatGitIgnoresAndCountsBelowItsDepth() {
        JsonValue r = ok("tree", "{\"depth\":1}");
        List<String> paths = r.get("entries").items().stream().map(e -> e.get("path").asString()).toList();
        assertEquals(".", paths.getFirst());
        assertFalse(paths.contains("build"));
        assertFalse(paths.contains("debug.log"));
        assertFalse(paths.contains(".git"));
        assertTrue(paths.contains("deep"));
        assertFalse(paths.contains("deep/a"), "below the depth asked for");
        JsonValue deep = r.get("entries").items().stream().filter(e -> e.get("path").asString().equals("deep"))
                .findFirst().orElseThrow();
        assertEquals(1, deep.get("files").asLong(0), "counted to any depth");
        assertEquals(3, deep.get("dirs").asLong(0));
        assertFalse(r.get("truncated").asBool(true), "depth is not truncation");
        assertTrue(ok("tree", "{\"ignored\":true,\"depth\":1}").get("entries").items().stream()
                .anyMatch(e -> e.get("path").asString().equals("build")));
        JsonValue cut = ok("tree", "{\"limit\":3}");
        assertEquals(4, cut.get("entries").size(), "the folder itself and three");
        assertTrue(cut.get("truncated").asBool(false));
    }

    // ---------------------------------------------------------------------------------------------- search

    @Test
    void searchFindsEachMatchWithItsLineAndColumn() {
        JsonValue r = ok("search", "{\"pattern\":\"TODO\"}");
        assertEquals(1, r.get("matches").size(), "debug.log is ignored: " + r.toJson());
        JsonValue m = r.get("matches").get(0);
        assertEquals("src/Main.java", m.get("path").asString());
        assertEquals(2, m.get("line").asLong(0));
        assertEquals(4, m.get("column").asLong(0));
        assertEquals("// TODO: tidy (soon)", m.get("text").asString());
        assertEquals(2, ok("search", "{\"pattern\":\"todo\",\"case_sensitive\":false}").get("matches").size());
        assertEquals(2, ok("search", "{\"pattern\":\"TODO\",\"ignored\":true}").get("matches").size());
    }

    @Test
    void searchCountsColumnsInCharactersAndSkipsWhatIsNotText() {
        JsonValue r = ok("search", "{\"pattern\":\"x marks\",\"literal\":true}");
        assertEquals(3, r.get("matches").get(0).get("column").asLong(0), "ü and 😀 are one character each");
        assertTrue(r.get("files_skipped").asLong(0) >= 2, "bin.dat and latin.txt: " + r.toJson());
    }

    @Test
    void searchTakesAGlobALiteralAndACap() {
        assertEquals(List.of("src/Main.java"), ok("search", "{\"pattern\":\"\\\\(soon\\\\)\",\"glob\":\"*.java\"}")
                .get("matches").items().stream().map(e -> e.get("path").asString()).toList());
        assertEquals(1, ok("search", "{\"pattern\":\"(soon)\",\"literal\":true}").get("matches").size());
        assertEquals(0, ok("search", "{\"pattern\":\"TODO\",\"glob\":\"deep/**\"}").get("matches").size());
        JsonValue capped = ok("search", "{\"pattern\":\"\\\\w\",\"max_matches\":3}");
        assertEquals(3, capped.get("matches").size());
        assertTrue(capped.get("truncated").asBool(false));
        assertTrue(capped.get("stopped").asString().contains("max_matches"));
        String bad = failed("search", "{\"pattern\":\"(unclosed\"}");
        assertTrue(bad.contains("not a valid regular expression") && bad.contains("literal"), bad);
    }

    @Test
    void aBacktrackingPatternIsStoppedAtItsDeadline() {
        Walk.Timed t = new Walk.Timed("a".repeat(100_000), System.nanoTime() - 1);
        boolean stopped = false;
        try {
            java.util.regex.Pattern.compile("(a+)+b").matcher(t).find();
        } catch (Walk.OutOfTime e) {
            stopped = true;
        }
        assertTrue(stopped);
    }
}
