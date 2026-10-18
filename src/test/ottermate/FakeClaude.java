import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * A stand-in for {@code claude.exe}, run as a single source file by {@code fake-claude.cmd}: for every message it is
 * sent, it answers with the same recorded turn, and it appends the message to a file so a check can read what the
 * agent was told. It stays up until its input closes, as the real one does.
 *
 * <p>Arguments: the recorded turn ({@code .jsonl}), and the file to append what it is sent to. The turn's
 * {@code system/init} line is printed once, at start, as the real CLI prints it.
 */
public class FakeClaude {

    public static void main(String[] args) throws IOException {
        List<String> turn = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        Path heard = Path.of(args[1]);
        Files.createDirectories(heard.toAbsolutePath().getParent());
        Files.writeString(heard, "", StandardCharsets.UTF_8);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        turn.stream().filter(FakeClaude::isInit).forEach(out::println);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            Files.writeString(heard, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            turn.stream().filter(l -> !isInit(l) && !l.isBlank()).forEach(out::println);
        }
    }

    private static boolean isInit(String line) {
        return line.contains("\"subtype\":\"init\"");
    }
}
