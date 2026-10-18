package dev.vexelray.maistro.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One of Claude Code's own {@code permission_suggestions} on a {@link AgentEvent.PermissionRequest}, in words a
 * person can say yes to, with the JSON to send back unchanged as {@code updatedPermissions} if they do.
 *
 * <h2>Only what can be said is offered</h2>
 *
 * <p>Maistro never makes a rule up: it passes back the CLI's own suggestion, verbatim. And it offers only the kinds
 * it can put into words ({@code addRules} with {@code allow}, {@code setMode}, {@code addDirectories}); anything
 * else is left out by {@link #describe}, so nothing is applied that the human was not shown. The scope is always
 * part of the words, because three of the four scopes outlive the conversation.
 *
 * @param what       what it allows: {@code Always allow Bash(npm test:*)}
 * @param scope      for how long and for whom: {@code in this project, for me}
 * @param persistent written to a settings file, so it outlives Claude Code's process
 * @param update     the suggestion as the CLI sent it
 */
public record Suggestion(String what, String scope, boolean persistent, JsonValue update) {

    /** The words a button shows: what, then scope. */
    public String label() {
        return what + " · " + scope;
    }

    /** The suggestions of a request that can be offered, in the CLI's order. */
    public static List<Suggestion> offered(JsonValue suggestions) {
        List<Suggestion> out = new ArrayList<>();
        for (JsonValue s : suggestions.items()) {
            describe(s).ifPresent(out::add);
        }
        return out;
    }

    /** {@code s} in words, or empty if it is a kind, a behaviour or a scope this code cannot say. */
    public static Optional<Suggestion> describe(JsonValue s) {
        Optional<Scope> scope = Scope.of(s.get("destination").asString());
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        String what = what(s);
        if (what.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Suggestion(what, scope.get().words, scope.get().persistent, s));
    }

    private static String what(JsonValue s) {
        String type = s.get("type").asString();
        if (type.equals("addRules") && s.get("behavior").asString().equals("allow")) {
            List<String> rules = new ArrayList<>();
            for (JsonValue r : s.get("rules").items()) {
                String tool = r.get("toolName").asString();
                if (tool.isEmpty()) {
                    return "";
                }
                String content = r.get("ruleContent").asString();
                rules.add(content.isEmpty() ? tool : tool + "(" + content + ")");
            }
            return rules.isEmpty() ? "" : "Always allow " + String.join(", ", rules);
        }
        if (type.equals("setMode")) {
            String mode = s.get("mode").asString();
            return mode.isEmpty() ? "" : "Switch to " + mode;
        }
        if (type.equals("addDirectories")) {
            List<String> dirs = new ArrayList<>();
            for (JsonValue d : s.get("directories").items()) {
                dirs.add(d.asString());
            }
            dirs.removeIf(String::isEmpty);
            return dirs.isEmpty() ? "" : "Allow working in " + String.join(", ", dirs);
        }
        return "";
    }

    /** Where an update is kept, in words, and whether it outlives the process. */
    private enum Scope {
        SESSION("session", "until Claude Code restarts", false),
        LOCAL("localSettings", "in this project, for me", true),
        PROJECT("projectSettings", "in this project, for everyone", true),
        USER("userSettings", "everywhere, for me", true);

        final String destination;
        final String words;
        final boolean persistent;

        Scope(String destination, String words, boolean persistent) {
            this.destination = destination;
            this.words = words;
            this.persistent = persistent;
        }

        static Optional<Scope> of(String destination) {
            for (Scope s : values()) {
                if (s.destination.equals(destination)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }
    }
}
