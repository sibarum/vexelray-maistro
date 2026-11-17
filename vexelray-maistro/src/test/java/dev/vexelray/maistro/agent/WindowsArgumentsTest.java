package dev.vexelray.maistro.agent;

import dev.vexelray.maistro.map.MapProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arguments reach a Windows program as they were given, double quotes and backslashes included. */
class WindowsArgumentsTest {

    @Test
    void aQuoteIsEscapedAndTheBackslashesBeforeItDoubled() {
        assertEquals("plain", AgentProcess.escapeQuotes("plain"));
        assertEquals("a \\\"b\\\" c", AgentProcess.escapeQuotes("a \"b\" c"));
        assertEquals("\\\"x", AgentProcess.escapeQuotes("\"x"));
        assertEquals("a\\\\\\\"b", AgentProcess.escapeQuotes("a\\\"b"));
        assertEquals("C:\\dir\\ \\\"", AgentProcess.escapeQuotes("C:\\dir\\ \""));
    }

    @Test
    void backslashesNotBeforeAQuoteStayAsTheyAre() {
        assertEquals("C:\\a\\b\\", AgentProcess.escapeQuotes("C:\\a\\b\\"));
        assertEquals("q\\\" C:\\a\\", AgentProcess.escapeQuotes("q\" C:\\a\\"));
    }

    @Test
    void theExecutableAndAShellScriptAreLeftAlone() {
        assertEquals(List.of("C:\\x\\claude.exe", "\\\"a\\\""),
                AgentProcess.forWindows(List.of("C:\\x\\claude.exe", "\"a\""), true));
        List<String> script = List.of("relay-claude.cmd", "\"a\"");
        assertEquals(script, AgentProcess.forWindows(script, true));
        List<String> strict = List.of("claude.exe", "\"a\"");
        assertEquals(strict, AgentProcess.forWindows(strict, false));
    }

    /**
     * The real thing: a {@code java.exe} started through {@link AgentProcess#SYSTEM} prints the arguments it read.
     * {@code java.exe}'s launcher splits its command line by the C runtime's rules, as {@code claude.exe} does.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aWindowsProgramReadsBackWhatItWasGiven() throws Exception {
        List<String> given = List.of(
                MapProtocol.INSTRUCTIONS,
                "{\"mcpServers\": {\"maistro\": {}}}",
                "\"",
                "\"quoted at both ends\"",
                "ends in a backslash\\",
                "C:\\Program Files\\x\\",
                "a\\\"b",
                "\\\\\"",
                "",
                "no-space\"inside");
        assertEquals(given, echo(given));
    }

    private static List<String> echo(List<String> args) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Path classes = Path.of(WindowsArgumentsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(java, "-cp", classes.toString(), Echo.class.getName()));
        command.addAll(args);
        AgentProcess p = AgentProcess.SYSTEM.launch(command, null, Map.copyOf(System.getenv()));
        p.stdin().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.stdout().transferTo(out);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        String err = new String(p.stderr().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.exitCode(), err);
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n", -1);
        List<String> read = new ArrayList<>();
        for (int i = 0; i + 1 < lines.length; i++) {
            read.add(unescape(lines[i].strip()));
        }
        return read;
    }

    private static String unescape(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i += 4) {
            sb.append((char) Integer.parseInt(line.substring(i, i + 4), 16));
        }
        return sb.toString();
    }

    /** Prints each argument on a line of its own, each char as four hex digits, so no encoding can blur it. */
    public static final class Echo {
        public static void main(String[] args) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) {
                for (char c : a.toCharArray()) {
                    sb.append(String.format("%04x", (int) c));
                }
                sb.append('\n');
            }
            System.out.print(sb);
        }
    }
}
