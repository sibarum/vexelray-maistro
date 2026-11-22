package dev.vexelray.maistro.tools.files;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pieces {@code tree}, {@code search} and {@code list} share: one folder's entries in a stable order, what kind
 * each is, and text that gives up when a search has run too long.
 *
 * <p>Links are never followed: a link is reported as one, and a walk does not go through it, so a link back up the
 * tree cannot loop. A {@code .git} folder is git's own, never the project's, and a walk does not enter it.
 */
final class Walk {

    private Walk() {
    }

    /** What a path is, as the tools say it. */
    static String kind(BasicFileAttributes a) {
        if (a.isSymbolicLink()) {
            return "link";
        }
        if (a.isDirectory()) {
            return "dir";
        }
        return a.isRegularFile() ? "file" : "other";
    }

    /** {@code p}'s attributes, the link itself rather than what it points at; null when it cannot be read. */
    static BasicFileAttributes attributes(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    /** A folder's entries, sorted by name (by UTF-16 code unit, the same on every OS); throws when it cannot be read. */
    static List<Path> entries(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** Whether a walk goes into folder {@code p}: never into git's own. */
    static boolean enters(Path p) {
        return !p.getFileName().toString().equals(".git");
    }

    /** Thrown from inside a regex match when the search's time is up. */
    static final class OutOfTime extends RuntimeException {
        OutOfTime() {
            super("out of time", null, false, false);
        }
    }

    /**
     * {@code text} as a regex sees it, checking the clock every few thousand characters read, so a pattern that
     * backtracks without end is stopped at the search's deadline rather than holding its thread for good.
     */
    static final class Timed implements CharSequence {

        private final String text;
        private final int from;
        private final int to;
        private final long deadline;
        private int reads;

        Timed(String text, long deadlineNanos) {
            this(text, 0, text.length(), deadlineNanos);
        }

        private Timed(String text, int from, int to, long deadlineNanos) {
            this.text = text;
            this.from = from;
            this.to = to;
            this.deadline = deadlineNanos;
        }

        @Override
        public int length() {
            return to - from;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() - deadline > 0) {
                throw new OutOfTime();
            }
            return text.charAt(from + index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text, from + start, from + end, deadline);
        }

        @Override
        public String toString() {
            return text.substring(from, to);
        }
    }
}
