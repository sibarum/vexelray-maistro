package dev.vexelray.maistro.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.vexelray.maistro.spi.FileNames;

import org.junit.jupiter.api.Test;

class PathsTest {

    /** Rules that refuse any name holding "bad", and take '|' as a separator, so the OS's part is seen to be used. */
    static final FileNames STRICT = new FileNames() {
        @Override
        public String separators(String given) {
            return given.replace('|', '/');
        }

        @Override
        public String problem(String path) {
            return path.contains("bad") ? "bad is refused" : "";
        }
    };

    private final Path work = Path.of("").toAbsolutePath().resolve("work");
    private final Paths paths = new Paths(STRICT, work);

    @Test
    void aRelativePathIsTakenFromTheWorkingFolderAndNormalized() {
        assertEquals(work.resolve("src").resolve("a.txt"), paths.resolve("src/x/../a.txt").path());
        assertEquals(work.getParent().resolve("other"), paths.resolve("../other").path());
    }

    @Test
    void theOsSaysWhatSeparatesAndWhatIsRefused() {
        assertEquals(work.resolve("src").resolve("a.txt"), paths.resolve("src|a.txt").path());
        Paths.Resolved r = paths.resolve("src/bad.txt");
        assertFalse(r.ok());
        assertEquals("bad is refused", r.problem());
    }

    @Test
    void emptyAndNulAreRefusedOnEveryOs() {
        assertFalse(paths.resolve("").ok());
        assertTrue(paths.resolve("a\0b").problem().contains("NUL"));
    }

    @Test
    void aPathIsShownRelativeWithForwardSlashesInsideTheWorkingFolder() {
        assertEquals("src/a.txt", paths.shown(work.resolve("src").resolve("a.txt")));
        assertEquals(".", paths.shown(work));
        Path outside = work.getParent().resolve("elsewhere");
        assertEquals(outside.toString(), paths.shown(outside));
    }

    @Test
    void theWorkingFolderIsAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> new Paths(STRICT, Path.of("relative")));
    }
}
