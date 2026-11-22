package dev.vexelray.maistro.tools.files;

import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.agent.JsonValue;
import dev.vexelray.maistro.tools.Tool;
import dev.vexelray.maistro.tools.files.Codec.Encoding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code maistro-fs} tools that only read: {@code stat}, {@code list}, {@code tree}, {@code read} and
 * {@code search} (agent-tools.md, <i>Filesystem</i>). Every argument is a JSON value and reaches the file system as
 * the agent wrote it; every result is structured, with a short line beside it; text is decoded only in an encoding
 * that is named or detected and said, and strictly ({@link Codec}). Paths are resolved by {@link Paths}, and shown
 * relative to the working folder with forward slashes when they are inside it.
 *
 * <p>Every answer is bounded: a cap on entries, on matches, on files searched, on text returned and on time, and a
 * result that reached one says so ({@code truncated}) with what to ask for next. Nothing here writes.
 */
public final class FileTools {

    /** The largest file read whole: as text, or to be described. A larger one is read by byte range, or hashed only. */
    static final long MAX_WHOLE = 64L << 20;

    /** The most text {@code read} returns in one answer, in characters; more is cut at a line, with the next line said. */
    static final int MAX_TEXT = 512 * 1024;

    /** The most bytes {@code read} returns in one answer, before base64. */
    static final int MAX_BYTES = 1 << 20;

    /** The largest file {@code search} looks inside; larger ones are counted as skipped. */
    static final long MAX_SEARCHED_FILE = 16L << 20;

    /** How long one {@code search} may run. */
    static final long SEARCH_NANOS = 30_000_000_000L;

    /** The most entries any walk visits before it stops and says so. */
    static final int MAX_VISITS = 200_000;

    /** The most a matched line shows, in characters; the rest is cut and said. */
    static final int MAX_LINE = 400;

    private final Paths paths;

    public FileTools(Paths paths) {
        this.paths = paths;
    }

    /** The tools, in the order they are listed. */
    public List<Tool> tools() {
        return List.of(
                tool("stat", """
                        Describes files and folders without reading them for you: kind (file, dir, link, other or \
                        missing), size, modified and created times, and for a file its sha256, its encoding as \
                        detected (utf-8, utf-8-bom, utf-16le-bom, utf-16be-bom; 'unknown' when it is not detectably \
                        text), its line-ending census (lf, crlf, cr counts, style, final newline) and line count. Give \
                        one path, or several. Paths are absolute or relative to the working folder.""",
                        STAT_IN, STAT_OUT, Set.of("path", "paths"), this::stat),
                tool("list", """
                        Lists one folder's entries, not recursively, sorted by name: each with its kind, size, \
                        modified time and whether git ignores it. An optional glob filters by name (* and ? within a \
                        name, [abc] classes). Capped by limit; a cut list says truncated.""",
                        LIST_IN, LIST_OUT, Set.of("path", "glob", "limit"), this::list),
                tool("tree", """
                        Outlines a folder to a depth: each entry with its path, kind and depth, and for each folder \
                        its total size and the files and folders under it, counted to any depth. Skips what git \
                        ignores (the repository's .gitignore files and info/exclude) unless ignored is true, and \
                        never enters .git. Capped by limit on the entries returned.""",
                        TREE_IN, TREE_OUT, Set.of("path", "depth", "ignored", "limit"), this::tree),
                tool("read", """
                        Reads a file exactly as it is. As text (the default) it returns the text unchanged, its \
                        encoding (detected, or the one you name), its line endings and the sha256 of the whole file, \
                        so what you read is what an edit will match. Bytes that are not valid in the encoding are an \
                        error naming their offset, never replaced. Optional start_line and end_line (1-based, \
                        inclusive) read part of it; numbered prefixes each line with its number and a tab. As bytes, \
                        it returns base64 from offset for length. Long text is cut at a line and says next_line.""",
                        READ_IN, READ_OUT, Set.of("path", "as", "encoding", "start_line", "end_line", "numbered",
                                "offset", "length"), this::read),
                tool("search", """
                        Searches the files under a folder for a regular expression (Java syntax), or for a literal \
                        string with literal true, line by line. Each match is its path, line, column (1-based, in \
                        Unicode characters) and the line's text. Skips what git ignores unless ignored is true, and \
                        files that are not detectably text, which it counts. glob limits the files: with no slash it \
                        matches the name at any depth, with one the path from the folder searched (** spans \
                        folders). Capped by max_matches, by files and by time; a cut search says truncated and why.""",
                        SEARCH_IN, SEARCH_OUT, Set.of("path", "pattern", "literal", "case_sensitive", "glob",
                                "ignored", "max_matches"), this::search));
    }

    // ------------------------------------------------------------------------------------------------- stat

    private Tool.Result stat(Args a) {
        List<String> given;
        if (a.has("paths")) {
            given = a.strings("paths", 1, 100);
            if (a.has("path")) {
                a.problem("give path or paths, not both");
            }
        } else {
            given = List.of(a.string("path"));
        }
        if (a.failed()) {
            return Tool.Result.error(a.problems());
        }
        List<JsonValue> entries = new ArrayList<>();
        int found = 0;
        for (String g : given) {
            JsonValue.Obj e = statOne(g);
            if (!e.get("kind").asString().equals("missing") && !e.has("problem")) {
                found++;
            }
            entries.add(e);
        }
        String line = given.size() == 1 ? summary(entries.getFirst())
                : found + " of " + given.size() + " paths described";
        return Tool.Result.structured(line, JsonValue.Obj.builder().put("entries", new JsonValue.Arr(entries)).build());
    }

    private JsonValue.Obj statOne(String given) {
        JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("path", given);
        Paths.Resolved r = paths.resolve(given);
        if (!r.ok()) {
            return b.put("kind", "missing").put("problem", r.problem()).build();
        }
        Path p = r.path();
        b.put("path", paths.shown(p)).put("absolute", p.toString());
        BasicFileAttributes at = Walk.attributes(p);
        if (at == null) {
            return b.put("kind", "missing").build();
        }
        String kind = Walk.kind(at);
        b.put("kind", kind).put("size", at.size())
                .put("modified", at.lastModifiedTime().toInstant().toString())
                .put("created", at.creationTime().toInstant().toString());
        if (kind.equals("link")) {
            try {
                b.put("target", Files.readSymbolicLink(p).toString());
            } catch (IOException e) {
                b.put("problem", "the link's target could not be read: " + e.getMessage());
            }
        }
        if (!kind.equals("file")) {
            return b.build();
        }
        try {
            if (at.size() > MAX_WHOLE) {
                return b.put("sha256", sha256(p)).put("encoding", "unknown")
                        .put("note", "larger than " + (MAX_WHOLE >> 20) + " MiB: hashed, not read for its text")
                        .build();
            }
            byte[] bytes = Files.readAllBytes(p);
            b.put("sha256", Codec.sha256(bytes));
            Optional<Encoding> e = Codec.detect(bytes);
            if (e.isEmpty()) {
                return b.put("encoding", "unknown").build();
            }
            String text = Codec.decode(bytes, e.get()).text();
            Codec.Endings end = Codec.endings(text);
            return b.put("encoding", e.get().label()).put("endings", endings(end))
                    .put("lines", end.lines(text.isEmpty())).build();
        } catch (IOException ex) {
            return b.put("problem", "it could not be read: " + ex.getMessage()).build();
        }
    }

    private static String summary(JsonValue e) {
        String kind = e.get("kind").asString();
        String path = e.get("path").asString();
        if (e.has("problem")) {
            return path + ": " + e.get("problem").asString();
        }
        if (kind.equals("file")) {
            return path + ": file, " + e.get("size").asLong(0) + " bytes, " + e.get("encoding").asString()
                    + (e.has("endings") ? ", " + e.get("endings").get("style").asString() : "");
        }
        return path + ": " + kind;
    }

    // ------------------------------------------------------------------------------------------------- list

    private Tool.Result list(Args a) {
        String given = a.string("path", ".");
        String glob = a.string("glob", "");
        long limit = a.integer("limit", 1000, 1, 10_000);
        if (glob.indexOf('/') >= 0) {
            a.problem("'glob' matches a name, so it holds no '/'");
        }
        if (a.failed()) {
            return Tool.Result.error(a.problems());
        }
        Paths.Resolved r = paths.resolve(given);
        if (!r.ok()) {
            return Tool.Result.error("list: " + r.problem());
        }
        Path dir = r.path();
        if (!Files.isDirectory(dir)) {
            return Tool.Result.error("list: " + paths.shown(dir) + " is not a folder" + (Files.exists(dir)
                    ? "; stat or read it instead" : ", or not there"));
        }
        Pattern names = glob.isEmpty() ? null : Pattern.compile(Ignores.regex(glob), Pattern.DOTALL);
        Ignores rules = Ignores.at(dir);
        List<Path> all;
        try {
            all = Walk.entries(dir);
        } catch (IOException e) {
            return Tool.Result.error("list: " + paths.shown(dir) + " could not be read: " + e.getMessage());
        }
        List<JsonValue> out = new ArrayList<>();
        int total = 0;
        for (Path p : all) {
            String name = p.getFileName().toString();
            if (names != null && !names.matcher(name).matches()) {
                continue;
            }
            total++;
            if (out.size() >= limit) {
                continue;
            }
            BasicFileAttributes at = Walk.attributes(p);
            JsonValue.Obj.Builder e = JsonValue.Obj.builder().put("name", name);
            if (at == null) {
                out.add(e.put("kind", "other").put("size", 0).put("ignored", false).build());
                continue;
            }
            boolean isDir = at.isDirectory();
            out.add(e.put("kind", Walk.kind(at)).put("size", at.size())
                    .put("modified", at.lastModifiedTime().toInstant().toString())
                    .put("ignored", !Walk.enters(p) || rules.ignored(p, isDir)).build());
        }
        boolean truncated = total > out.size();
        return Tool.Result.structured(paths.shown(dir) + ": " + total + " entries" + (truncated ? ", " + out.size()
                + " shown" : ""), JsonValue.Obj.builder()
                .put("path", paths.shown(dir)).put("absolute", dir.toString())
                .put("entries", new JsonValue.Arr(out)).put("total", total).put("truncated", truncated).build());
    }

    // ------------------------------------------------------------------------------------------------- tree

    /** One entry of a tree as it is found; a folder's totals are filled in once its contents are counted. */
    private static final class Node {
        final String path;
        final String kind;
        final int depth;
        long size;
        long files;
        long dirs;

        Node(String path, String kind, int depth) {
            this.path = path;
            this.kind = kind;
            this.depth = depth;
        }
    }

    /** What a walk may still spend, and whether it ran out. */
    private static final class Budget {
        int visits;
        boolean exhausted;
        int unreadable;
        /** Entries within the depth asked for that were left out because the limit was reached. */
        int cut;
    }

    private Tool.Result tree(Args a) {
        String given = a.string("path", ".");
        long depth = a.integer("depth", 3, 0, 50);
        boolean seeIgnored = a.bool("ignored", false);
        long limit = a.integer("limit", 2000, 1, 50_000);
        if (a.failed()) {
            return Tool.Result.error(a.problems());
        }
        Paths.Resolved r = paths.resolve(given);
        if (!r.ok()) {
            return Tool.Result.error("tree: " + r.problem());
        }
        Path root = r.path();
        if (!Files.isDirectory(root)) {
            return Tool.Result.error("tree: " + paths.shown(root) + " is not a folder");
        }
        List<Node> nodes = new ArrayList<>();
        Budget budget = new Budget();
        Node top = new Node(paths.shown(root), "dir", 0);
        long[] totals = walkTree(root, seeIgnored ? Ignores.NONE : Ignores.at(root), seeIgnored, 1, (int) depth,
                (int) limit, nodes, budget);
        top.size = totals[0];
        top.files = totals[1];
        top.dirs = totals[2];
        nodes.addFirst(top);
        boolean truncated = budget.cut > 0 || budget.exhausted;
        List<JsonValue> out = new ArrayList<>();
        for (Node n : nodes) {
            JsonValue.Obj.Builder e = JsonValue.Obj.builder().put("path", n.path).put("kind", n.kind)
                    .put("depth", n.depth).put("size", n.size);
            if (n.kind.equals("dir")) {
                e.put("files", n.files).put("dirs", n.dirs);
            }
            out.add(e.build());
        }
        JsonValue.Obj.Builder data = JsonValue.Obj.builder().put("path", top.path).put("absolute", root.toString())
                .put("entries", new JsonValue.Arr(out)).put("files", top.files).put("dirs", top.dirs)
                .put("size", top.size).put("truncated", truncated).put("complete", !budget.exhausted);
        if (budget.unreadable > 0) {
            data.put("unreadable", budget.unreadable);
        }
        return Tool.Result.structured(top.path + ": " + top.files + " files, " + top.dirs + " folders, " + top.size
                + " bytes" + (truncated ? " (" + (out.size() - 1) + " entries shown)" : "")
                + (budget.exhausted ? "; stopped after " + MAX_VISITS + " entries, so the counts are partial" : ""),
                data.build());
    }

    /**
     * Walks {@code dir}, listing entries to {@code maxDepth} while fewer than {@code limit} are listed, and counting
     * everything below. Returns size, files and folders.
     */
    private long[] walkTree(Path dir, Ignores rules, boolean seeIgnored, int depth, int maxDepth, int limit,
                            List<Node> nodes, Budget budget) {
        long size = 0;
        long files = 0;
        long dirs = 0;

        List<Path> entries;
        try {
            entries = Walk.entries(dir);
        } catch (IOException e) {
            budget.unreadable++;
            return new long[]{0, 0, 0};
        }
        for (Path p : entries) {
            if (budget.visits++ >= MAX_VISITS) {
                budget.exhausted = true;
                break;
            }
            BasicFileAttributes at = Walk.attributes(p);
            if (at == null) {
                budget.unreadable++;
                continue;
            }
            boolean isDir = at.isDirectory();
            if (!Walk.enters(p) || !seeIgnored && rules.ignored(p, isDir)) {
                continue;
            }
            boolean listed = depth <= maxDepth && nodes.size() < limit;
            if (depth <= maxDepth && !listed) {
                budget.cut++;
            }
            Node n = new Node(paths.shown(p), Walk.kind(at), depth);
            if (listed) {
                nodes.add(n);
            }
            if (isDir) {
                dirs++;
                long[] t = walkTree(p, seeIgnored ? rules : rules.enter(p), seeIgnored, depth + 1, maxDepth, limit,
                        nodes, budget);
                n.size = t[0];
                n.files = t[1];
                n.dirs = t[2];
                size += t[0];
                files += t[1];
                dirs += t[2];

            } else {
                n.size = at.size();
                size += at.size();
                files++;
            }
        }
        return new long[]{size, files, dirs};
    }

    // ------------------------------------------------------------------------------------------------- read

    private Tool.Result read(Args a) {
        String given = a.string("path");
        String as = a.oneOf("as", "text", List.of("text", "bytes"));
        String encodingName = a.string("encoding", "");
        long start = a.integer("start_line", 1, 1, Long.MAX_VALUE);
        long end = a.integer("end_line", Long.MAX_VALUE, 1, Long.MAX_VALUE);
        boolean numbered = a.bool("numbered", false);
        long offset = a.integer("offset", 0, 0, Long.MAX_VALUE);
        long length = a.integer("length", MAX_BYTES, 0, MAX_BYTES);
        Optional<Encoding> named = encodingName.isEmpty() ? Optional.empty() : Encoding.named(encodingName);
        if (!encodingName.isEmpty() && named.isEmpty()) {
            a.problem("'encoding' is one of " + Encoding.labels() + ", not '" + encodingName + "'");
        }
        if (as.equals("bytes") && (a.has("start_line") || a.has("end_line") || a.has("numbered")
                || a.has("encoding"))) {
            a.problem("as bytes takes offset and length, not lines or an encoding");
        }
        if (as.equals("text") && (a.has("offset") || a.has("length"))) {
            a.problem("offset and length are for as bytes; text is read by start_line and end_line");
        }
        if (end < start) {
            a.problem("end_line " + end + " is before start_line " + start);
        }
        if (a.failed()) {
            return Tool.Result.error(a.problems());
        }
        Paths.Resolved r = paths.resolve(given);
        if (!r.ok()) {
            return Tool.Result.error("read: " + r.problem());
        }
        Path p = r.path();
        if (!Files.isRegularFile(p)) {
            return Tool.Result.error("read: " + paths.shown(p) + (Files.isDirectory(p)
                    ? " is a folder; list or tree it instead" : " is not there"));
        }
        try {
            return as.equals("bytes") ? readBytes(p, offset, length) : readText(p, named, start, end, numbered);
        } catch (IOException e) {
            return Tool.Result.error("read: " + paths.shown(p) + " could not be read: " + e.getMessage());
        }
    }

    private Tool.Result readBytes(Path p, long offset, long length) throws IOException {
        long size = Files.size(p);
        if (offset > size) {
            return Tool.Result.error("read: offset " + offset + " is past the end of " + paths.shown(p) + " ("
                    + size + " bytes)");
        }
        int n = (int) Math.min(length, size - offset);
        byte[] chunk = new byte[n];
        try (SeekableByteChannel c = Files.newByteChannel(p)) {
            c.position(offset);
            ByteBuffer buf = ByteBuffer.wrap(chunk);
            while (buf.hasRemaining() && c.read(buf) > 0) {
                // fill
            }
        }
        boolean more = offset + n < size;
        JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("path", paths.shown(p)).put("absolute", p.toString())
                .put("size", size).put("sha256", sha256(p)).put("offset", offset).put("length", n)
                .put("base64", Base64.getEncoder().encodeToString(chunk)).put("truncated", more);
        if (more) {
            b.put("next_offset", offset + n);
        }
        return Tool.Result.structured(paths.shown(p) + ": bytes " + offset + " to " + (offset + n) + " of " + size,
                b.build());
    }

    private Tool.Result readText(Path p, Optional<Encoding> named, long start, long end, boolean numbered)
            throws IOException {
        long size = Files.size(p);
        if (size > MAX_WHOLE) {
            return Tool.Result.error("read: " + paths.shown(p) + " is " + size + " bytes, more than the "
                    + (MAX_WHOLE >> 20) + " MiB read as text; read it as bytes with offset and length");
        }
        byte[] bytes = Files.readAllBytes(p);
        Optional<Encoding> enc = named.isPresent() ? named : Codec.detect(bytes);
        if (enc.isEmpty()) {
            return Tool.Result.error("read: " + paths.shown(p) + " is not detectably text (no byte-order mark, and "
                    + "not valid UTF-8 without NULs); name its encoding (" + Encoding.labels()
                    + ") or read it as bytes");
        }
        Codec.Decoded d = Codec.decode(bytes, enc.get());
        if (!d.ok()) {
            return Tool.Result.error("read: " + paths.shown(p) + ": " + d.problem() + "; name another encoding, or "
                    + "read it as bytes");
        }
        String text = d.text();
        Codec.Endings census = Codec.endings(text);
        long lines = census.lines(text.isEmpty());
        List<int[]> spans = lineSpans(text);
        if (start > Math.max(lines, 1)) {
            return Tool.Result.error("read: start_line " + start + " is past the last line of " + paths.shown(p)
                    + " (" + lines + ")");
        }
        int first = (int) start - 1;
        int last = (int) Math.min(end, spans.size()) - 1;
        StringBuilder out = new StringBuilder();
        int shownLast = first - 1;
        for (int i = first; i <= last; i++) {
            String line = text.substring(spans.get(i)[0], spans.get(i)[1]);
            String piece = numbered ? (i + 1) + "\t" + line : line;
            if (out.length() + piece.length() > MAX_TEXT) {
                if (i > first) {
                    break;
                }
                // One line longer than an answer holds: text is never cut mid-line, so say how to read it instead.
                long at = Codec.encode(text.substring(0, spans.get(i)[0]), enc.get()).length;
                return Tool.Result.error("read: line " + (i + 1) + " of " + paths.shown(p) + " is " + line.length()
                        + " characters, more than the " + MAX_TEXT + " one answer holds; read it as bytes from "
                        + "offset " + at);
            }
            out.append(piece);
            shownLast = i;
        }
        boolean truncated = shownLast < last;
        JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("path", paths.shown(p)).put("absolute", p.toString())
                .put("size", size).put("sha256", Codec.sha256(bytes)).put("encoding", enc.get().label())
                .put("endings", endings(census)).put("lines", lines)
                .put("start_line", spans.isEmpty() ? 0 : first + 1).put("end_line", spans.isEmpty() ? 0 : shownLast + 1)
                .put("text", out.toString()).put("truncated", truncated);
        if (truncated) {
            b.put("next_line", shownLast + 2);
        }
        String range = spans.isEmpty() ? "empty" : start == 1 && shownLast == spans.size() - 1 ? lines + " lines"
                : "lines " + (first + 1) + " to " + (shownLast + 1) + " of " + lines;
        return Tool.Result.structured(paths.shown(p) + ": " + range + ", " + enc.get().label() + ", "
                + census.style() + (truncated ? "; more from line " + (shownLast + 2) : ""), b.build());
    }

    /** Each line's span in {@code text}, its ending included, so the pieces put back together are the text. */
    static List<int[]> lineSpans(String text) {
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        int from = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                out.add(new int[]{from, i + 1});
                from = i + 1;
            }
        }
        if (from < n) {
            out.add(new int[]{from, n});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- search

    /** What a search has found so far, and why it stopped if it did. */
    private static final class Found {
        final List<JsonValue> matches = new ArrayList<>();
        int searched;
        int skipped;
        int visits;
        String stopped = "";
    }

    private Tool.Result search(Args a) {
        String given = a.string("path", ".");
        String pattern = a.string("pattern");
        boolean literal = a.bool("literal", false);
        boolean caseSensitive = a.bool("case_sensitive", true);
        String glob = a.string("glob", "");
        boolean seeIgnored = a.bool("ignored", false);
        long max = a.integer("max_matches", 200, 1, 5000);
        if (a.has("pattern") && pattern.isEmpty()) {
            a.problem("'pattern' is empty");
        }
        Pattern re = null;
        if (!pattern.isEmpty()) {
            try {
                re = Pattern.compile(literal ? Pattern.quote(pattern) : pattern,
                        caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException e) {
                a.problem("'pattern' is not a valid regular expression: " + e.getDescription() + " at index "
                        + e.getIndex() + "; set literal to true to search for it as written");
            }
        }
        if (a.failed()) {
            return Tool.Result.error(a.problems());
        }
        Paths.Resolved r = paths.resolve(given);
        if (!r.ok()) {
            return Tool.Result.error("search: " + r.problem());
        }
        Path root = r.path();
        if (!Files.isDirectory(root)) {
            return Tool.Result.error("search: " + paths.shown(root) + " is not a folder; read the file instead");
        }
        Pattern files = glob.isEmpty() ? null
                : Pattern.compile(Ignores.regex(glob.indexOf('/') < 0 ? "**/" + glob : glob), Pattern.DOTALL);
        Found f = new Found();
        long deadline = System.nanoTime() + SEARCH_NANOS;
        searchIn(root, root, seeIgnored ? Ignores.NONE : Ignores.at(root), seeIgnored, files, re, (int) max,
                deadline, f);
        boolean truncated = !f.stopped.isEmpty();
        JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("path", paths.shown(root))
                .put("absolute", root.toString()).put("matches", new JsonValue.Arr(f.matches))
                .put("files_searched", f.searched).put("files_skipped", f.skipped).put("truncated", truncated);
        if (truncated) {
            b.put("stopped", f.stopped);
        }
        return Tool.Result.structured(f.matches.size() + " matches in " + f.searched + " files"
                + (f.skipped > 0 ? ", " + f.skipped + " skipped" : "") + (truncated ? "; stopped: " + f.stopped : ""),
                b.build());
    }

    private void searchIn(Path root, Path dir, Ignores rules, boolean seeIgnored, Pattern files, Pattern re, int max,
                          long deadline, Found f) {
        List<Path> entries;
        try {
            entries = Walk.entries(dir);
        } catch (IOException e) {
            f.skipped++;
            return;
        }
        for (Path p : entries) {
            if (!f.stopped.isEmpty()) {
                return;
            }
            if (f.visits++ >= MAX_VISITS) {
                f.stopped = "visited " + MAX_VISITS + " entries; search a smaller folder or narrow the glob";
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                f.stopped = "ran " + SEARCH_NANOS / 1_000_000_000L + " s; search a smaller folder or a simpler pattern";
                return;
            }
            BasicFileAttributes at = Walk.attributes(p);
            if (at == null) {
                f.skipped++;
                continue;
            }
            boolean isDir = at.isDirectory();
            if (!Walk.enters(p) || !seeIgnored && rules.ignored(p, isDir)) {
                continue;
            }
            if (isDir) {
                searchIn(root, p, seeIgnored ? rules : rules.enter(p), seeIgnored, files, re, max, deadline, f);
                continue;
            }
            if (!at.isRegularFile()) {
                continue;
            }
            String rel = root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
            if (files != null && !files.matcher(rel).matches()) {
                continue;
            }
            searchFile(p, re, max, deadline, at.size(), f);
        }
    }

    private void searchFile(Path p, Pattern re, int max, long deadline, long size, Found f) {
        if (size > MAX_SEARCHED_FILE) {
            f.skipped++;
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch (IOException e) {
            f.skipped++;
            return;
        }
        Optional<Encoding> enc = Codec.detect(bytes);
        if (enc.isEmpty()) {
            f.skipped++;
            return;
        }
        String text = Codec.decode(bytes, enc.get()).text();
        f.searched++;
        String shown = paths.shown(p);
        List<int[]> spans = lineSpans(text);
        try {
            for (int i = 0; i < spans.size(); i++) {
                int from = spans.get(i)[0];
                int to = spans.get(i)[1];
                while (to > from && (text.charAt(to - 1) == '\n' || text.charAt(to - 1) == '\r')) {
                    to--;
                }
                String line = text.substring(from, to);
                Matcher m = re.matcher(new Walk.Timed(line, deadline));
                while (m.find()) {
                    if (f.matches.size() >= max) {
                        f.stopped = "reached max_matches (" + max + ")";
                        return;
                    }
                    boolean cut = line.length() > MAX_LINE;
                    JsonValue.Obj.Builder b = JsonValue.Obj.builder().put("path", shown).put("line", i + 1)
                            .put("column", line.codePointCount(0, m.start()) + 1)
                            .put("text", cut ? line.substring(0, safeCut(line, MAX_LINE)) : line);
                    if (cut) {
                        b.put("text_truncated", true);
                    }
                    f.matches.add(b.build());
                    if (m.end() == m.start() && m.end() >= line.length()) {
                        break;
                    }
                }
            }
        } catch (Walk.OutOfTime e) {
            f.stopped = "ran " + SEARCH_NANOS / 1_000_000_000L + " s; search a smaller folder or a simpler pattern";
        }
    }

    /** {@code max}, or one less when that would split a surrogate pair. */
    private static int safeCut(String s, int max) {
        return Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
    }

    // ------------------------------------------------------------------------------------------------- shared

    private static JsonValue endings(Codec.Endings e) {
        return JsonValue.Obj.builder().put("style", e.style()).put("lf", e.lf()).put("crlf", e.crlf())
                .put("cr", e.cr()).put("final_newline", e.finalNewline()).build();
    }

    /** The sha256 of a file, streamed, for one too large to hold. */
    private static String sha256(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(p)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static Tool tool(String name, String description, JsonValue in, JsonValue out, Set<String> known,
                             Function<Args, Tool.Result> run) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonValue inputSchema() {
                return in;
            }

            @Override
            public JsonValue outputSchema() {
                return out;
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public Tool.Result call(JsonValue arguments) {
                return run.apply(new Args(name, arguments, known));
            }
        };
    }

    // ------------------------------------------------------------------------------------------------- schemas

    private static final String PATH_DESC = "absolute, or relative to the working folder";

    private static final String ENDINGS = """
            {"type":"object","properties":{"style":{"type":"string","enum":["lf","crlf","cr","mixed","none"]},\
            "lf":{"type":"integer"},"crlf":{"type":"integer"},"cr":{"type":"integer"},\
            "final_newline":{"type":"boolean"}},"required":["style","lf","crlf","cr","final_newline"]}""";

    static final JsonValue STAT_IN = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string","description":"one path, %s"},\
            "paths":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":100,\
            "description":"several paths, in place of path"}},"additionalProperties":false}"""
            .formatted(PATH_DESC));

    static final JsonValue STAT_OUT = Json.parse("""
            {"type":"object","properties":{"entries":{"type":"array","items":{"type":"object","properties":{\
            "path":{"type":"string"},"absolute":{"type":"string"},\
            "kind":{"type":"string","enum":["file","dir","link","other","missing"]},\
            "size":{"type":"integer"},"modified":{"type":"string"},"created":{"type":"string"},\
            "target":{"type":"string"},"sha256":{"type":"string"},"encoding":{"type":"string"},\
            "endings":%s,"lines":{"type":"integer"},"note":{"type":"string"},"problem":{"type":"string"}},\
            "required":["path","kind"]}}},"required":["entries"]}""".formatted(ENDINGS));

    static final JsonValue LIST_IN = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string","description":"the folder, %s; default ."},\
            "glob":{"type":"string","description":"only names matching this, such as *.java"},\
            "limit":{"type":"integer","minimum":1,"maximum":10000,"description":"most entries returned; default 1000"}},\
            "additionalProperties":false}""".formatted(PATH_DESC));

    static final JsonValue LIST_OUT = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string"},"absolute":{"type":"string"},\
            "entries":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},\
            "kind":{"type":"string","enum":["file","dir","link","other"]},"size":{"type":"integer"},\
            "modified":{"type":"string"},"ignored":{"type":"boolean"}},"required":["name","kind","size","ignored"]}},\
            "total":{"type":"integer"},"truncated":{"type":"boolean"}},\
            "required":["path","absolute","entries","total","truncated"]}""");

    static final JsonValue TREE_IN = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string","description":"the folder, %s; default ."},\
            "depth":{"type":"integer","minimum":0,"maximum":50,"description":"how deep entries are listed; default 3"},\
            "ignored":{"type":"boolean","description":"include what git ignores; default false"},\
            "limit":{"type":"integer","minimum":1,"maximum":50000,"description":"most entries listed; default 2000"}},\
            "additionalProperties":false}""".formatted(PATH_DESC));

    static final JsonValue TREE_OUT = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string"},"absolute":{"type":"string"},\
            "entries":{"type":"array","items":{"type":"object","properties":{"path":{"type":"string"},\
            "kind":{"type":"string","enum":["file","dir","link","other"]},"depth":{"type":"integer"},\
            "size":{"type":"integer"},"files":{"type":"integer"},"dirs":{"type":"integer"}},\
            "required":["path","kind","depth","size"]}},"files":{"type":"integer"},"dirs":{"type":"integer"},\
            "size":{"type":"integer"},"truncated":{"type":"boolean"},"complete":{"type":"boolean"},\
            "unreadable":{"type":"integer"}},\
            "required":["path","absolute","entries","files","dirs","size","truncated","complete"]}""");

    static final JsonValue READ_IN = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string","description":"the file, %s"},\
            "as":{"type":"string","enum":["text","bytes"],"description":"default text"},\
            "encoding":{"type":"string","enum":["utf-8","utf-8-bom","utf-16le","utf-16le-bom","utf-16be",\
            "utf-16be-bom","latin-1"],"description":"read as this rather than as detected"},\
            "start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1},\
            "numbered":{"type":"boolean","description":"prefix each line with its number and a tab"},\
            "offset":{"type":"integer","minimum":0,"description":"as bytes: where to start"},\
            "length":{"type":"integer","minimum":0,"maximum":%d,"description":"as bytes: how many"}},\
            "required":["path"],"additionalProperties":false}""".formatted(PATH_DESC, MAX_BYTES));

    static final JsonValue READ_OUT = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string"},"absolute":{"type":"string"},\
            "size":{"type":"integer"},"sha256":{"type":"string"},"encoding":{"type":"string"},\
            "endings":%s,"lines":{"type":"integer"},"start_line":{"type":"integer"},"end_line":{"type":"integer"},\
            "text":{"type":"string"},"offset":{"type":"integer"},"length":{"type":"integer"},\
            "base64":{"type":"string"},"truncated":{"type":"boolean"},"next_line":{"type":"integer"},\
            "next_offset":{"type":"integer"}},"required":["path","absolute","size","sha256","truncated"]}"""
            .formatted(ENDINGS));

    static final JsonValue SEARCH_IN = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string","description":"the folder, %s; default ."},\
            "pattern":{"type":"string","description":"a Java regular expression, matched within each line"},\
            "literal":{"type":"boolean","description":"search for pattern as written; default false"},\
            "case_sensitive":{"type":"boolean","description":"default true"},\
            "glob":{"type":"string","description":"only files matching, such as *.java or src/**/*.md"},\
            "ignored":{"type":"boolean","description":"include what git ignores; default false"},\
            "max_matches":{"type":"integer","minimum":1,"maximum":5000,"description":"default 200"}},\
            "required":["pattern"],"additionalProperties":false}""".formatted(PATH_DESC));

    static final JsonValue SEARCH_OUT = Json.parse("""
            {"type":"object","properties":{"path":{"type":"string"},"absolute":{"type":"string"},\
            "matches":{"type":"array","items":{"type":"object","properties":{"path":{"type":"string"},\
            "line":{"type":"integer"},"column":{"type":"integer"},"text":{"type":"string"},\
            "text_truncated":{"type":"boolean"}},"required":["path","line","column","text"]}},\
            "files_searched":{"type":"integer"},"files_skipped":{"type":"integer"},"truncated":{"type":"boolean"},\
            "stopped":{"type":"string"}},\
            "required":["path","absolute","matches","files_searched","files_skipped","truncated"]}""");
}
