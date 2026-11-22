package dev.vexelray.maistro.tools;

import dev.vexelray.gui.automation.cli.Reply;
import dev.vexelray.maistro.agent.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The tools that look at the other running VexelRay applications, over their automation sockets, as {@code ottermate}
 * does: {@code apps} lists them, {@code tree} and {@code find} read a window's semantic tree, and {@code shot} hands
 * the agent a picture of a window. All of them only read, so none takes the hand, and none needs the human's say-so.
 *
 * <p>An application is picked by its name or its pid, and may be left out when exactly one is running, which is
 * {@code ottermate}'s rule too. A window is picked by name, number or a unique beginning of its name, as
 * {@code ottermate window} takes it; left out, it is the application's first.
 */
public final class AppTools {

    /** How long {@code shot} waits for the picture's file to be complete once the application said it was written. */
    static final long SHOT_COMPLETE_MS = 2_000;

    /** A PNG ends with this chunk type, then its four-byte CRC. */
    private static final byte[] IEND = "IEND".getBytes(StandardCharsets.US_ASCII);

    private final Apps apps;
    private final Path shots;

    /**
     * @param apps  where the applications are found and reached
     * @param shots the folder {@code shot} has an application write its picture into; made when first needed, and
     *              each picture is deleted once read
     */
    public AppTools(Apps apps, Path shots) {
        this.apps = apps;
        this.shots = shots;
    }

    /** The four tools, in the order they are listed to the agent. */
    public List<Tool> tools() {
        return List.of(
                tool("apps", """
                        Lists the VexelRay desktop applications running on this machine with their automation socket \
                        on: name, pid, window title, start time and working directory. The other tools take one of \
                        these names (or pids) as app.""",
                        schema(List.of(), List.of()), args -> apps()),
                tool("tree", """
                        Reads the semantic tree of a running VexelRay application's window: one line per node, with \
                        its ref, role, accessible name, @landmark, box [x,y wxh], and state (focused, clipped to, \
                        offscreen, hidden, scrolls). Use it to see what is on screen and what can be addressed. \
                        Prefer landmarks to refs: refs last one run only.""",
                        schema(List.of(APP, WINDOW), List.of()), args -> read(args, "tree")),
                tool("find", """
                        Finds the nodes of a running VexelRay application's window whose role, name or landmark \
                        contains the text, case-insensitively, and lists them as tree does. Finding nothing is not \
                        an error.""",
                        schema(List.of(APP, WINDOW, TEXT), List.of("text")),
                        args -> read(args, "find " + args.get("text").asString().strip())),
                tool("shot", """
                        Takes a screenshot of a running VexelRay application's window, as the application draws it, \
                        and returns the picture. Works for a window that is covered or minimized.""",
                        schema(List.of(APP, WINDOW), List.of()), this::shot));
    }

    // ------------------------------------------------------------------ the tools

    private Tool.Result apps() {
        List<Apps.App> running = apps.running();
        if (running.isEmpty()) {
            return Tool.Result.text(NONE);
        }
        StringBuilder sb = new StringBuilder("app\tpid\ttitle\tstarted\tcwd");
        for (Apps.App a : running) {
            sb.append('\n').append(a.name()).append('\t').append(a.pid()).append('\t').append(a.title())
                    .append('\t').append(a.started()).append('\t').append(a.cwd());
        }
        return Tool.Result.text(sb.toString());
    }

    /** Sends {@code command} to the chosen window, and answers with its reply. */
    private Tool.Result read(JsonValue args, String command) {
        Picked p = pick(args);
        if (p.app() == null) {
            return Tool.Result.error(p.why());
        }
        try {
            Reply r = last(apps.send(p.app(), commands(args, command)));
            return r.err() ? Tool.Result.error(r.text()) : Tool.Result.text(r.text());
        } catch (IOException e) {
            return unreachable(p.app(), e);
        }
    }

    private Tool.Result shot(JsonValue args) {
        Picked p = pick(args);
        if (p.app() == null) {
            return Tool.Result.error(p.why());
        }
        Path file = shots.resolve("shot-" + UUID.randomUUID() + ".png").toAbsolutePath();
        try {
            Files.createDirectories(shots);
            // Forward slashes: the path is resolved by the other application, and that form reads the same on every OS.
            Reply r = last(apps.send(p.app(), commands(args, "shot " + file.toString().replace('\\', '/'))));
            if (r.err()) {
                return Tool.Result.error(r.text());
            }
            byte[] png = complete(file);
            if (png == null) {
                return Tool.Result.error("The application said " + r.first() + ", but " + file
                        + " did not become a complete PNG within " + SHOT_COMPLETE_MS + " ms.");
            }
            return Tool.Result.image(p.app().name() + ": " + r.first(), png);
        } catch (IOException e) {
            return unreachable(p.app(), e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // A stray picture in Maistro's own shots folder; the next run's are named afresh.
            }
        }
    }

    // ------------------------------------------------------------------ picking an application

    /** The application the arguments name, or why there is none. */
    private record Picked(Apps.App app, String why) {
    }

    private Picked pick(JsonValue args) {
        String wanted = args.get("app").asString().strip();
        List<Apps.App> running = apps.running();
        if (running.isEmpty()) {
            return new Picked(null, NONE);
        }
        if (wanted.isEmpty()) {
            return running.size() == 1 ? new Picked(running.getFirst(), "")
                    : new Picked(null, "Several applications are running; say which with app: " + names(running));
        }
        List<Apps.App> matches = new ArrayList<>();
        for (Apps.App a : running) {
            if (a.name().equalsIgnoreCase(wanted) || Long.toString(a.pid()).equals(wanted)) {
                matches.add(a);
            }
        }
        if (matches.size() == 1) {
            return new Picked(matches.getFirst(), "");
        }
        if (matches.isEmpty()) {
            return new Picked(null, "No running application is called " + wanted + ". Running: " + names(running));
        }
        return new Picked(null, "More than one " + wanted + " is running; give its pid as app: " + names(matches));
    }

    private static String names(List<Apps.App> apps) {
        List<String> out = new ArrayList<>();
        for (Apps.App a : apps) {
            out.add(a.name() + " (pid " + a.pid() + ")");
        }
        return String.join(", ", out);
    }

    private static List<String> commands(JsonValue args, String command) {
        String window = args.get("window").asString().strip();
        return window.isEmpty() ? List.of(command) : List.of("window " + window, command);
    }

    private static Reply last(List<Reply> replies) {
        return replies.getLast();
    }

    private static Tool.Result unreachable(Apps.App app, IOException e) {
        return Tool.Result.error("Could not reach " + app.name() + " (pid " + app.pid() + ", port " + app.port()
                + "): " + e.getMessage());
    }

    /**
     * The picture once its file ends as a PNG does, or null. The application answers as soon as the file has bytes,
     * so the last of them may still be on their way.
     */
    static byte[] complete(Path file) throws IOException {
        long deadline = System.nanoTime() + SHOT_COMPLETE_MS * 1_000_000L;
        while (true) {
            if (Files.isRegularFile(file)) {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length >= 12 && Arrays.equals(bytes, bytes.length - 8, bytes.length - 4, IEND, 0, 4)) {
                    return bytes;
                }
            }
            if (System.nanoTime() > deadline) {
                return null;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ schemas

    private static final String NONE = "No VexelRay application is running with its automation socket on. One "
            + "started with --automation=0 (or -Dautomation=0 under Maven) has it on.";

    private record Property(String name, String description) {
    }

    private static final Property APP = new Property("app",
            "The application's name or pid, from apps. May be left out when exactly one is running.");
    private static final Property WINDOW = new Property("window",
            "The window's name, number, or a unique beginning of its name. Left out, the application's first window.");
    private static final Property TEXT = new Property("text", "What to look for in a role, name or landmark.");

    private static JsonValue schema(List<Property> properties, List<String> required) {
        JsonValue.Obj.Builder props = JsonValue.Obj.builder();
        for (Property p : properties) {
            props.put(p.name(), JsonValue.Obj.builder().put("type", "string").put("description", p.description())
                    .build());
        }
        JsonValue.Obj.Builder s = JsonValue.Obj.builder().put("type", "object").put("properties", props.build());
        if (!required.isEmpty()) {
            s.put("required", JsonValue.Arr.ofStrings(required));
        }
        return s.build();
    }

    /**
     * A tool that only looks: every one here lists, reads a tree or takes a picture, so each runs without asking.
     * The acting tools ({@code click}, {@code type}, ...) will not be made here.
     */
    private static Tool tool(String name, String description, JsonValue schema, Function<JsonValue, Tool.Result> run) {
        return new Tool() {
            @Override
            public boolean readOnly() {
                return true;
            }

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
                return schema;
            }

            @Override
            public Tool.Result call(JsonValue arguments) {
                return run.apply(arguments);
            }
        };
    }
}
