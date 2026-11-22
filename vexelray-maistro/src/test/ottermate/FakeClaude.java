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
        if (args[0].equals(SIGNED_OUT_ONCE)) {
            authStatus(Path.of(args[1]));
            return;
        }
        if (args[0].equals(STATUS_AFTER_LOGIN)) {
            authStatusAfterLogin(Path.of(args[1]));
            return;
        }
        if (args[0].equals(LOGIN)) {
            authLogin(Path.of(args[1]));
            return;
        }
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

    /**
     * In place of the turn: answer {@code claude auth status --json} signed out the first time one Maistro asks, and
     * signed in every time after, as if {@code claude auth login} had been run in between. Which Maistro is asking is
     * the grandparent process (Maistro, then {@code cmd.exe}, then this), kept with the count in {@code file}.
     */
    private static final String SIGNED_OUT_ONCE = "--auth-status-signed-out-once";

    private static void authStatus(Path file) throws IOException {
        long maistro = ProcessHandle.current().parent().flatMap(ProcessHandle::parent).map(ProcessHandle::pid)
                .orElse(-1L);
        boolean asked = Files.exists(file)
                && Files.readString(file, StandardCharsets.UTF_8).strip().equals(Long.toString(maistro));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, Long.toString(maistro), StandardCharsets.UTF_8);
        System.out.println(asked
                ? "{\"loggedIn\": true, \"authMethod\": \"fake\"}"
                : "{\"loggedIn\": false, \"authMethod\": \"none\"}");
        System.exit(asked ? 0 : 1);
    }

    /**
     * In place of the turn: answer {@code claude auth status --json} signed in only once {@link #LOGIN} has signed this
     * Maistro in. The state is kept in {@code file} as {@code <maistro pid> <logins tried> [in]}.
     */
    private static final String STATUS_AFTER_LOGIN = "--auth-status-after-login";

    /**
     * In place of the turn: {@code claude auth login}, as the real one prints it through a pipe (its lead, then the page's
     * address on a line of its own). The first time one Maistro asks, it waits there until it is ended, as a person who
     * never finishes in the browser would leave it, so Cancel has something to stop. Every time after, it is signed in at
     * once: {@code Login successful.} and exit 0.
     */
    private static final String LOGIN = "--auth-login";

    private static long maistro() {
        return ProcessHandle.current().parent().flatMap(ProcessHandle::parent).map(ProcessHandle::pid).orElse(-1L);
    }

    /** {@code file}'s words if this Maistro wrote them, else none. */
    private static String[] loginState(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new String[0];
        }
        String[] words = Files.readString(file, StandardCharsets.UTF_8).strip().split(" ");
        return words[0].equals(Long.toString(maistro())) ? words : new String[0];
    }

    private static void authStatusAfterLogin(Path file) throws IOException {
        String[] state = loginState(file);
        boolean in = state.length > 2 && state[2].equals("in");
        System.out.println(in
                ? "{\"loggedIn\": true, \"authMethod\": \"fake\"}"
                : "{\"loggedIn\": false, \"authMethod\": \"none\"}");
        System.exit(in ? 0 : 1);
    }

    private static void authLogin(Path file) throws IOException {
        String[] state = loginState(file);
        int tried = (state.length > 1 ? Integer.parseInt(state[1]) : 0) + 1;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, maistro() + " " + tried, StandardCharsets.UTF_8);
        System.out.println("Opening browser to sign in…");
        System.out.println("If the browser didn't open, visit:");
        System.out.println("https://claude.ai/oauth/authorize?code=true&client_id=fake&state=" + tried);
        if (tried == 1) {
            while (true) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        Files.writeString(file, maistro() + " " + tried + " in", StandardCharsets.UTF_8);
        System.out.println("Login successful.");
        System.exit(0);
    }

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
