package dev.vexelray.maistro.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowsFileNamesTest {

    private final WindowsFileNames names = new WindowsFileNames();

    private String problem(String p) {
        return names.problem(names.separators(p));
    }

    @Test
    void bothSlashesSeparate() {
        assertEquals("C:\\a\\b\\c", names.separators("C:/a\\b/c"));
    }

    @Test
    void whatWindowsWouldChangeIsRefusedByName() {
        assertTrue(problem("src/notes.").contains("'notes.' ends with a dot"));
        assertTrue(problem("src/notes ").contains("ends with a space"));
        assertTrue(problem("src/NUL").contains("device name"));
        assertTrue(problem("src/nul.txt").contains("device name"), "an extension does not make it a file");
        assertTrue(problem("com1.log").contains("COM1"));
        assertTrue(problem("LPT²").contains("device name"));
        assertTrue(problem("a.txt:secret").contains("data stream"));
        assertTrue(problem("what?.txt").contains("'?'"));
        assertTrue(problem("a\u0001b").contains("\\u0001"));
        assertTrue(problem("\\\\?\\C:\\x").contains("verbatim"));
    }

    @Test
    void ordinaryNamesPass() {
        assertEquals("", problem("C:/Users/x/a file with spaces.txt"));
        assertEquals("", problem("../sibling/naïve résumé.md"));
        assertEquals("", problem("console.txt"), "CON is reserved, CONSOLE is not");
        assertEquals("", problem("NULL"));
        assertEquals("", problem(".gitignore"));
        assertEquals("", problem("it's.txt"));
        assertEquals("", problem("\\\\server\\share\\a.txt"), "a UNC path is a plain path");
    }
}
