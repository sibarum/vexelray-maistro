import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for {@code claude.exe}, run as a single source file by {@code fake-claude.cmd}: for every message it is
 * sent, it answers with the same recorded turn, and it appends the message to a file so a check can read what the
 * agent was told. It stays up until its input closes, as the real one does.
 *
 * <p>Arguments: the recorded turn ({@code .jsonl}), and the file to append what it is sent to. The turn's
 * {@code system/init} line is printed once, at start, as the real CLI prints it. A line {@code #await <type>} in the
 * turn is not printed: it waits for a line of that type on the input, which is how a turn asks permission and waits
 * for Maistro's {@code control_response} before it goes on.
 */
public class FakeClaude {

    public static void main(String[] args) throws IOException {
        List<String> all = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        Path heard = Path.of(args[1]);
        Files.createDirectories(heard.toAbsolutePath().getParent());
        // Appended to, never emptied: a branch is a second process, and what the first was told must stay readable.
        record(heard, "#process " + ProcessHandle.current().pid());
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        all.stream().filter(FakeClaude::isInit).forEach(out::println);
        List<String> plain = new ArrayList<>();
        Map<String, List<String>> when = new LinkedHashMap<>();
        List<String> into = plain;
        for (String l : all) {
            if (l.startsWith(WHEN)) {
                into = new ArrayList<>();
                when.put(l.substring(WHEN.length()).strip(), into);
            } else {
                into.add(l);
            }
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            record(heard, line);
            List<String> turn = plain;
            for (Map.Entry<String, List<String>> e : when.entrySet()) {
                if (line.contains(e.getKey())) {
                    turn = e.getValue();
                    break;
                }
            }
            for (String l : turn) {
                if (l.startsWith(AWAIT)) {
                    if (!awaitLine(in, heard, l.substring(AWAIT.length()).strip())) {
                        return;
                    }
                } else if (!isInit(l) && !l.isBlank()) {
                    out.println(l);
                }
            }
        }
    }

    /**
     * A line of the turn that is not printed but waits: {@code #await control_response} reads (and records) input
     * until a line of that type arrives, as the real CLI waits for the answer to a permission prompt.
     */
    private static final String AWAIT = "#await ";

    /**
     * Starts the lines played instead when a message contains the rest of the line: {@code #when You are a branch}
     * answers a branch's first message with a turn of its own. Lines before the first {@code #when} are the turn
     * for every other message.
     */
    private static final String WHEN = "#when ";

    /** Read until a line of {@code type}; false if the input closed first. */
    private static boolean awaitLine(BufferedReader in, Path heard, String type) throws IOException {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            record(heard, line);
            if (line.contains("\"type\":\"" + type + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static void record(Path heard, String line) throws IOException {
        Files.writeString(heard, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static boolean isInit(String line) {
        return line.contains("\"subtype\":\"init\"");
    }
}
