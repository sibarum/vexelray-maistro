package dev.vexelray.maistro.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What git would ignore under a folder, by git's own rules ({@code gitignore(5)}), read from the files git reads: the
 * repository's {@code .git/info/exclude}, then every {@code .gitignore} from the repository's top down to the folder
 * being looked at. The user's global excludes file is not read: it is that person's, and a tool should see the
 * repository as everyone sharing it does.
 *
 * <p><b>The rules.</b> A blank line or a {@code #} line says nothing; {@code \#} and {@code \!} begin a pattern with
 * that character, and trailing spaces are dropped unless escaped. {@code !} re-includes. A trailing {@code /} matches
 * only folders. A pattern with a {@code /} at its start or middle is anchored to its file's folder; one without matches
 * at any depth. {@code *} and {@code ?} never match {@code /}; {@code [...]} is a class ({@code !} or {@code ^}
 * negates it); {@code **} spans folders when it is a whole part ({@code **}{@code /a}, {@code a/**}, {@code a/**}{@code /b})
 * and is a plain {@code *} anywhere else. The last matching pattern decides, and a deeper file's patterns come after
 * a shallower one's. A folder git ignores is not entered, so nothing below it can be re-included, as git has it: the
 * walker that uses this does not descend into one.
 *
 * <p>Immutable: {@link #enter} returns the rules for a folder below, sharing these.
 */
public final class Ignores {

    /** One pattern, compiled, with the folder it is relative to. */
    private record Rule(Path base, Pattern pattern, boolean negated, boolean dirOnly) {
    }

    private final List<Rule> rules;

    private Ignores(List<Rule> rules) {
        this.rules = rules;
    }

    /** Ignoring nothing, for a walk that was asked to see everything, or a folder outside any repository. */
    public static final Ignores NONE = new Ignores(List.of());

    /**
     * The rules in force at folder {@code dir} (absolute): its repository's exclude file and every {@code .gitignore}
     * from the repository's top down to {@code dir}, {@code dir}'s own included. Outside a repository, only
     * {@code dir}'s own {@code .gitignore} and those between it and the walk are read, as git would read none: a
     * folder with no repository gets {@link #NONE} plus what {@link #enter} adds.
     */
    public static Ignores at(Path dir) {
        Path top = null;
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                top = p;
                break;
            }
        }
        if (top == null) {
            return NONE;
        }
        Ignores i = NONE.with(top, top.resolve(".git").resolve("info").resolve("exclude"));
        List<Path> chain = new ArrayList<>();
        for (Path p = dir; p != null && p.startsWith(top); p = p.getParent()) {
            chain.addFirst(p);
        }
        for (Path p : chain) {
            i = i.enter(p);
        }
        return i;
    }

    /** These rules with folder {@code dir}'s own {@code .gitignore} after them, for what is below {@code dir}. */
    public Ignores enter(Path dir) {
        return with(dir, dir.resolve(".gitignore"));
    }

    /** These rules, and the patterns in {@code file} (relative to {@code base}) if it is there to read. */
    private Ignores with(Path base, Path file) {
        if (!Files.isRegularFile(file)) {
            return this;
        }
        List<String> lines;
        try {
            lines = List.of(Files.readString(file, StandardCharsets.UTF_8).split("\r?\n", -1));
        } catch (IOException | java.io.UncheckedIOException e) {
            return this; // git skips a file it cannot read, too
        }
        return parse(base, lines);
    }

    /** These rules, and {@code lines} as a {@code .gitignore} in {@code base}. */
    Ignores parse(Path base, List<String> lines) {
        List<Rule> out = new ArrayList<>(rules);
        for (String line : lines) {
            Rule r = rule(base, line);
            if (r != null) {
                out.add(r);
            }
        }
        return out.size() == rules.size() ? this : new Ignores(List.copyOf(out));
    }

    /**
     * Whether git would ignore {@code path} (absolute, below the folders these rules came from), a folder when
     * {@code dir}. Its folders above are not asked about: a walk never reaches a path under an ignored folder.
     */
    public boolean ignored(Path path, boolean dir) {
        boolean ignored = false;
        for (Rule r : rules) {
            if (r.dirOnly && !dir) {
                continue;
            }
            if (!path.startsWith(r.base) || path.equals(r.base)) {
                continue;
            }
            String rel = r.base.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
            if (r.pattern.matcher(rel).matches()) {
                ignored = !r.negated;
            }
        }
        return ignored;
    }

    /** One line of a {@code .gitignore} as a rule, or null for a line that says nothing. */
    static Rule rule(Path base, String line) {
        String p = stripTrailingSpaces(line);
        if (p.isEmpty() || p.startsWith("#")) {
            return null;
        }
        boolean negated = false;
        if (p.startsWith("!")) {
            negated = true;
            p = p.substring(1);
        } else if (p.startsWith("\\!") || p.startsWith("\\#")) {
            p = p.substring(1);
        }
        boolean dirOnly = false;
        if (p.endsWith("/") && !p.endsWith("\\/")) {
            dirOnly = true;
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return null;
        }
        boolean anchored = p.indexOf('/') >= 0;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!anchored) {
            p = "**/" + p;
        }
        return new Rule(base, Pattern.compile(regex(p), Pattern.DOTALL), negated, dirOnly);
    }

    /** {@code line} without its trailing spaces, keeping one escaped with a back slash. */
    static String stripTrailingSpaces(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            if (end >= 2 && line.charAt(end - 2) == '\\') {
                return line.substring(0, end - 2) + " " ;
            }
            end--;
        }
        return line.substring(0, end);
    }

    /** A glob, as git reads one in a {@code .gitignore}, as a regex over a {@code /}-separated relative path. */
    static String regex(String glob) {
        StringBuilder r = new StringBuilder();
        int n = glob.length();
        int i = 0;
        while (i < n) {
            char c = glob.charAt(i);
            boolean partStart = i == 0 || glob.charAt(i - 1) == '/';
            if (c == '*' && i + 1 < n && glob.charAt(i + 1) == '*' && partStart
                    && (i + 2 == n || glob.charAt(i + 2) == '/')) {
                if (i + 2 == n) {
                    r.append(".*"); // a trailing /**: everything inside
                    i += 2;
                } else {
                    r.append("(?:.*/)?"); // **/ : any folders, or none
                    i += 3;
                }
                continue;
            }
            switch (c) {
                case '*' -> {
                    while (i + 1 < n && glob.charAt(i + 1) == '*') {
                        i++; // consecutive stars not a whole part are one star
                    }
                    r.append("[^/]*");
                }
                case '?' -> r.append("[^/]");
                case '[' -> {
                    int close = classEnd(glob, i);
                    if (close < 0) {
                        r.append("\\[");
                    } else {
                        r.append(charClass(glob.substring(i + 1, close)));
                        i = close;
                    }
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        i++;
                        r.append(Pattern.quote(String.valueOf(glob.charAt(i))));
                    } else {
                        r.append("\\\\");
                    }
                }
                default -> r.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return r.toString();
    }

    /** Where the class opened at {@code open} closes, or -1 if it does not. A {@code ]} first in it is a member. */
    private static int classEnd(String glob, int open) {
        int i = open + 1;
        if (i < glob.length() && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == ']') {
            i++;
        }
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == ']') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * The inside of a glob class as a regex class, never matching {@code /}: a look-ahead keeps it out, rather than a
     * class intersection, whose meaning beside a negation has changed between Java versions.
     */
    private static String charClass(String inside) {
        StringBuilder r = new StringBuilder("(?!/)[");
        int i = 0;
        if (!inside.isEmpty() && (inside.charAt(0) == '!' || inside.charAt(0) == '^')) {
            r.append('^');
            i = 1;
        }
        for (; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '\\' && i + 1 < inside.length()) {
                i++;
                c = inside.charAt(i);
                r.append('\\').append(c);
            } else if (c == '[' || c == ']' || c == '&' || c == '^' || c == '\\') {
                r.append('\\').append(c);
            } else {
                r.append(c);
            }
        }
        return r.append(']').toString();
    }
}
