package dev.vexelray.maistro.tools.files;

import dev.vexelray.maistro.agent.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A tool call's arguments, read strictly: a name the tool does not take, a value of the wrong type or out of range,
 * or a missing required one is a problem the call reports, never a default quietly put in its place. Problems are
 * gathered, so one answer names them all. The CLI checks the input schema too, but the tool does not rest on that.
 */
final class Args {

    private final String tool;
    private final JsonValue args;
    private final List<String> problems = new ArrayList<>();

    /** {@code args} for {@code tool}, which takes the names in {@code known} and nothing else. */
    Args(String tool, JsonValue args, Set<String> known) {
        this.tool = tool;
        this.args = args;
        for (String k : args.members().keySet()) {
            if (!known.contains(k)) {
                problems.add("'" + k + "' is not an argument of " + tool + " (it takes " + String.join(", ",
                        known.stream().sorted().toList()) + ")");
            }
        }
    }

    /** Whether anything was wrong. */
    boolean failed() {
        return !problems.isEmpty();
    }

    /** What was wrong, in one sentence each. */
    String problems() {
        return tool + ": " + String.join("; ", problems);
    }

    boolean has(String key) {
        return args.has(key) && !args.get(key).isNull();
    }

    /** A string, required. */
    String string(String key) {
        JsonValue v = args.get(key);
        if (!v.isString()) {
            problems.add(v.isNull() ? "'" + key + "' is required" : "'" + key + "' is a string, not " + v.toJson());
            return "";
        }
        return v.asString();
    }

    /** A string, or {@code fallback} when absent. */
    String string(String key, String fallback) {
        return has(key) ? string(key) : fallback;
    }

    /** One of {@code allowed}, or {@code fallback} when absent. */
    String oneOf(String key, String fallback, List<String> allowed) {
        String s = string(key, fallback);
        if (!allowed.contains(s)) {
            problems.add("'" + key + "' is one of " + String.join(", ", allowed) + ", not '" + s + "'");
        }
        return s;
    }

    /** A whole number in {@code [min, max]}, or {@code fallback} when absent. */
    long integer(String key, long fallback, long min, long max) {
        if (!has(key)) {
            return fallback;
        }
        JsonValue v = args.get(key);
        double d = v.asDouble(Double.NaN);
        if (!v.isNumber() || d != Math.rint(d)) {
            problems.add("'" + key + "' is a whole number, not " + v.toJson());
            return fallback;
        }
        long n = v.asLong(fallback);
        if (n < min || n > max) {
            problems.add("'" + key + "' is from " + min + " to " + max + ", not " + n);
            return fallback;
        }
        return n;
    }

    /** True or false, or {@code fallback} when absent. */
    boolean bool(String key, boolean fallback) {
        if (!has(key)) {
            return fallback;
        }
        JsonValue v = args.get(key);
        if (!v.isBool()) {
            problems.add("'" + key + "' is true or false, not " + v.toJson());
            return fallback;
        }
        return v.asBool(fallback);
    }

    /** An array of strings, required, of {@code min} to {@code max} items. */
    List<String> strings(String key, int min, int max) {
        JsonValue v = args.get(key);
        if (!v.isArray()) {
            problems.add(v.isNull() ? "'" + key + "' is required" : "'" + key + "' is an array of strings");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonValue item : v.items()) {
            if (!item.isString()) {
                problems.add("'" + key + "' holds only strings, not " + item.toJson());
                return List.of();
            }
            out.add(item.asString());
        }
        if (out.size() < min || out.size() > max) {
            problems.add("'" + key + "' holds " + min + " to " + max + " items, not " + out.size());
        }
        return out;
    }

    /** A problem found after reading, for the same answer. */
    void problem(String what) {
        problems.add(what);
    }
}
