package dev.vexelray.maistro.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable JSON value. Read one with {@link Json#parse(String)}, write one with {@link #toJson()}.
 *
 * <p>Every accessor is defined on every value and answers a default instead of throwing: {@code get("k")} on a
 * string, or on an object without {@code k}, is {@link Null#NULL}; {@code asString()} on a number is {@code ""}.
 * So a path through a document can be read in one expression, {@code line.get("message").get("id").asString()},
 * and a shape the CLI changes degrades to empty values rather than an exception on the reader thread.
 */
public interface JsonValue {

    /** The member {@code key} of an object, or {@link Null#NULL} when this is not an object or has no such key. */
    default JsonValue get(String key) {
        return Null.NULL;
    }

    /** The element at {@code index} of an array, or {@link Null#NULL} when this is not an array or it is out of range. */
    default JsonValue get(int index) {
        return Null.NULL;
    }

    /** Whether this is an object with a member {@code key} (whose value may be {@code null}). */
    default boolean has(String key) {
        return false;
    }

    /** The string's text; {@code ""} for anything but a string. */
    default String asString() {
        return asString("");
    }

    /** The string's text; {@code fallback} for anything but a string. */
    default String asString(String fallback) {
        return fallback;
    }

    /** The number as a long (truncated toward zero if it has a fraction); {@code fallback} for anything else. */
    default long asLong(long fallback) {
        return fallback;
    }

    /** The number as a double; {@code fallback} for anything else. */
    default double asDouble(double fallback) {
        return fallback;
    }

    /** The boolean; {@code fallback} for anything else. */
    default boolean asBool(boolean fallback) {
        return fallback;
    }

    /** The elements of an array, in order; empty for anything else. */
    default List<JsonValue> items() {
        return List.of();
    }

    /** The members of an object, in document order; empty for anything else. */
    default Map<String, JsonValue> members() {
        return Map.of();
    }

    /** Elements of an array or members of an object; 0 for anything else. */
    default int size() {
        return 0;
    }

    default boolean isNull() {
        return false;
    }

    default boolean isObject() {
        return false;
    }

    default boolean isArray() {
        return false;
    }

    default boolean isString() {
        return false;
    }

    default boolean isNumber() {
        return false;
    }

    default boolean isBool() {
        return false;
    }

    /** This value as compact JSON text, on one line. */
    default String toJson() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb);
        return sb.toString();
    }

    /** Appends this value as compact JSON text. */
    void writeTo(StringBuilder sb);

    // ------------------------------------------------------------------------------------------------------------

    /** A JSON object. Member order is kept as read or as built; keys are unique (a later duplicate wins). */
    record Obj(Map<String, JsonValue> members) implements JsonValue {
        public static final Obj EMPTY = new Obj(Map.of());

        public Obj {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(members)));
        }

        /** Starts an object; see {@link Builder}. */
        public static Builder builder() {
            return new Builder();
        }

        @Override
        public JsonValue get(String key) {
            JsonValue v = members.get(key);
            return v == null ? Null.NULL : v;
        }

        @Override
        public boolean has(String key) {
            return members.containsKey(key);
        }

        @Override
        public int size() {
            return members.size();
        }

        @Override
        public boolean isObject() {
            return true;
        }

        /** The keys, in order. */
        public Set<String> keys() {
            return members.keySet();
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonValue> e : members.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                Json.quote(sb, e.getKey());
                sb.append(':');
                e.getValue().writeTo(sb);
            }
            sb.append('}');
        }

        @Override
        public String toString() {
            return toJson();
        }

        /** Builds an {@link Obj} member by member, in order. A {@code null} value is written as JSON null. */
        public static final class Builder {
            private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

            public Builder put(String key, JsonValue value) {
                members.put(Objects.requireNonNull(key), value == null ? Null.NULL : value);
                return this;
            }

            public Builder put(String key, String value) {
                return put(key, value == null ? Null.NULL : new Str(value));
            }

            public Builder put(String key, long value) {
                return put(key, Num.of(value));
            }

            public Builder put(String key, double value) {
                return put(key, Num.of(value));
            }

            public Builder put(String key, boolean value) {
                return put(key, Bool.of(value));
            }

            public Obj build() {
                return new Obj(members);
            }
        }
    }

    /** A JSON array. */
    record Arr(List<JsonValue> items) implements JsonValue {
        public static final Arr EMPTY = new Arr(List.of());

        public Arr {
            items = List.copyOf(items);
        }

        public static Arr of(JsonValue... items) {
            return new Arr(List.of(items));
        }

        /** An array of strings. */
        public static Arr ofStrings(List<String> strings) {
            List<JsonValue> out = new ArrayList<>(strings.size());
            for (String s : strings) {
                out.add(new Str(s));
            }
            return new Arr(out);
        }

        @Override
        public JsonValue get(int index) {
            return index >= 0 && index < items.size() ? items.get(index) : Null.NULL;
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public boolean isArray() {
            return true;
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                items.get(i).writeTo(sb);
            }
            sb.append(']');
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    /** A JSON string. */
    record Str(String value) implements JsonValue {
        public Str {
            Objects.requireNonNull(value);
        }

        @Override
        public String asString(String fallback) {
            return value;
        }

        @Override
        public boolean isString() {
            return true;
        }

        @Override
        public void writeTo(StringBuilder sb) {
            Json.quote(sb, value);
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    /**
     * A JSON number, kept as its literal text so nothing is lost to a conversion until a caller asks for one.
     * Build one with {@link #of(long)} or {@link #of(double)}; the parser only makes ones whose text is valid JSON.
     */
    record Num(String literal) implements JsonValue {
        public Num {
            Objects.requireNonNull(literal);
        }

        public static Num of(long value) {
            return new Num(Long.toString(value));
        }

        /** A finite double; NaN and the infinities have no JSON form and are written as 0. */
        public static Num of(double value) {
            if (!Double.isFinite(value)) {
                return new Num("0");
            }
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return new Num(Long.toString((long) value));
            }
            return new Num(Double.toString(value));
        }

        @Override
        public long asLong(long fallback) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException notPlain) {
                try {
                    return new BigDecimal(literal).longValue();
                } catch (NumberFormatException bad) {
                    return fallback;
                }
            }
        }

        @Override
        public double asDouble(double fallback) {
            try {
                return Double.parseDouble(literal);
            } catch (NumberFormatException bad) {
                return fallback;
            }
        }

        @Override
        public boolean isNumber() {
            return true;
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append(literal);
        }

        @Override
        public String toString() {
            return literal;
        }
    }

    /** {@code true} or {@code false}. */
    enum Bool implements JsonValue {
        TRUE,
        FALSE;

        public static Bool of(boolean b) {
            return b ? TRUE : FALSE;
        }

        @Override
        public boolean asBool(boolean fallback) {
            return this == TRUE;
        }

        @Override
        public boolean isBool() {
            return true;
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append(this == TRUE ? "true" : "false");
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    /** JSON {@code null}, and the answer to every lookup that finds nothing. */
    enum Null implements JsonValue {
        NULL;

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append("null");
        }

        @Override
        public String toString() {
            return "null";
        }
    }
}
