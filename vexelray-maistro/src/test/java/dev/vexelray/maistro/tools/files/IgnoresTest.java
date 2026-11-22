package dev.vexelray.maistro.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoresTest {

    @TempDir
    Path repo;

    /** The repository every test here looks at: nested ignore files, negations, classes, anchors and {@code **}. */
    private static final Map<String, String> IGNORE_FILES = new LinkedHashMap<>();

    static {
        IGNORE_FILES.put(".gitignore", String.join("\n",
                "# build output",
                "target/",
                "*.log",
                "!keep.log",
                "/root-only.txt",
                "docs/*.tmp",
                "**/cache",
                "logs/**",
                "a/**/z.txt",
                "file[0-9].txt",
                "[!x]y.md",
                "\\#hash.txt",
                "\\!bang.txt",
                "trail\\ ",
                "spaced   ",
                ""));
        IGNORE_FILES.put("sub/.gitignore", String.join("\n",
                "*.txt",
                "!important.txt",
                "/local.md",
                ""));
    }

    /** Every path made in the repository, folders ending with a slash. */
    private static final List<String> PATHS = List.of(
            "target/", "target/a.class", "src/target/", "src/target/b.class",
            "x.log", "keep.log", "sub/keep.log", "deep/q.log",
            "root-only.txt", "docs/root-only.txt",
            "docs/a.tmp", "docs/more/b.tmp", "other/docs/c.tmp",
            "cache/", "src/cache/", "src/cache/c.bin", "cachefile",
            "logs/", "logs/a.txt", "logs/deeper/b.md",
            "a/z.txt", "a/b/z.txt", "a/b/c/z.txt", "b/a/z.txt",
            "file1.txt", "fileA.txt", "ay.md", "xy.md",
            "#hash.txt", "!bang.txt", "spaced",
            "sub/notes.txt", "sub/important.txt", "sub/local.md", "sub/deeper/local.md", "sub/deeper/n.txt",
            "plain.md", "src/Main.java");

    private void make() throws IOException {
        for (String p : PATHS) {
            Path f = repo.resolve(p);
            if (p.endsWith("/")) {
                Files.createDirectories(f);
            } else {
                Files.createDirectories(f.getParent());
                Files.writeString(f, "x");
            }
        }
        for (Map.Entry<String, String> e : IGNORE_FILES.entrySet()) {
            Files.writeString(repo.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** Whether {@code rel} is ignored as a walk finds it: not under an ignored folder, then by its own rules. */
    private boolean ignoredByWalk(String rel) {
        Ignores rules = Ignores.at(repo);
        Path at = repo;
        String[] parts = rel.replaceAll("/$", "").split("/");
        for (int i = 0; i < parts.length; i++) {
            Path next = at.resolve(parts[i]);
            boolean dir = Files.isDirectory(next);
            if (rules.ignored(next, dir)) {
                return true;
            }
            if (dir) {
                rules = rules.enter(next);
            }
            at = next;
        }
        return false;
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.excludesFile=", "-c", "core.quotepath=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    @Test
    void agreesWithGitOnEveryPath() throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        make();
        git("init", "-q");
        List<String> asked = PATHS.stream().map(p -> p.replaceAll("/$", "")).toList();
        String stdin = String.join("\0", asked) + "\0";
        Process p = new ProcessBuilder("git", "-c", "core.excludesFile=", "check-ignore", "-z", "--stdin")
                .directory(repo.toFile()).start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        Set<String> gitIgnores = new TreeSet<>();
        for (String s : out.split("\0")) {
            if (!s.isEmpty()) {
                gitIgnores.add(s);
            }
        }
        Set<String> ours = new TreeSet<>();
        for (String a : asked) {
            if (ignoredByWalk(a)) {
                ours.add(a);
            }
        }
        assertEquals(gitIgnores, ours);
        assertTrue(gitIgnores.size() > 15, "the cases ignore a good share: " + gitIgnores);
    }

    @Test
    void theRulesAsTheyAreMeant() throws Exception {
        make();
        Files.createDirectories(repo.resolve(".git"));
        assertTrue(ignoredByWalk("target/"));
        assertTrue(ignoredByWalk("src/target/b.class"), "a pattern with no slash matches at any depth");
        assertTrue(ignoredByWalk("x.log"));
        assertFalse(ignoredByWalk("keep.log"), "re-included");
        assertTrue(ignoredByWalk("root-only.txt"));
        assertFalse(ignoredByWalk("docs/root-only.txt"), "anchored to its file's folder");
        assertTrue(ignoredByWalk("docs/a.tmp"));
        assertFalse(ignoredByWalk("docs/more/b.tmp"), "* does not cross /");
        assertFalse(ignoredByWalk("cachefile"));
        assertTrue(ignoredByWalk("a/z.txt"), "**/ may be no folders");
        assertTrue(ignoredByWalk("a/b/c/z.txt"));
        assertFalse(ignoredByWalk("b/a/z.txt"));
        assertTrue(ignoredByWalk("file1.txt"));
        assertFalse(ignoredByWalk("fileA.txt"));
        assertTrue(ignoredByWalk("ay.md"));
        assertFalse(ignoredByWalk("xy.md"), "a negated class");
        assertTrue(ignoredByWalk("#hash.txt"));
        assertTrue(ignoredByWalk("!bang.txt"));
        assertTrue(ignoredByWalk("spaced"), "trailing spaces are dropped");
        assertTrue(ignoredByWalk("sub/notes.txt"));
        assertFalse(ignoredByWalk("sub/important.txt"), "a deeper file's rules come later");
        assertTrue(ignoredByWalk("sub/local.md"));
        assertFalse(ignoredByWalk("sub/deeper/local.md"));
        assertFalse(ignoredByWalk("plain.md"));
    }

    /** A name ending in a space cannot be made on Windows, so the line itself is checked. */
    @Test
    void trailingSpacesAreDroppedUnlessEscaped() {
        assertEquals("trail ", Ignores.stripTrailingSpaces("trail\\ "));
        assertEquals("trail ", Ignores.stripTrailingSpaces("trail\\    "));
        assertEquals("spaced", Ignores.stripTrailingSpaces("spaced   "));
        assertEquals("a\\ b", Ignores.stripTrailingSpaces("a\\ b"), "an escape inside a name is the glob's");
    }

    @Test
    void outsideARepositoryNothingIsIgnored() throws Exception {
        make();
        assertFalse(Ignores.at(repo).ignored(repo.resolve("x.log"), false), "no .git here, so git reads nothing");
    }
}
