package dev.vexelray.maistro.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A small, strict JSON reader and writer (RFC 8259), with no reflection: text becomes a {@link JsonValue} tree and
 * back, and nothing is bound to classes.
 *
 * <p>Strict where it matters for a line protocol: one value per text with only whitespace around it, no trailing
 * commas, no comments, no single quotes, no leading zeros, no raw control characters inside strings, and nesting
 * no deeper than {@value #MAX_DEPTH}. {@code \}{@code uXXXX} escapes are decoded to UTF-16 code units, so a
 * surrogate pair written as two escapes becomes the one code point it names; a lone surrogate is kept as is.
 * When writing, a lone surrogate is escaped so the output stays valid when encoded as UTF-8.
 */
public final class Json {

    /** The deepest nesting {@link #parse} accepts, so a hostile line cannot overflow the reader thread's stack. */
    public static final int MAX_DEPTH = 512;

    private Json() {
    }

    /**
     * Parses one JSON text.
     *
     * @throws JsonException with the offending offset, if {@code text} is not exactly one valid JSON value
     */
    public static JsonValue parse(String text) {
        Reader r = new Reader(text);
        r.skipWhitespace();
        JsonValue v = r.value(0);
        r.skipWhitespace();
        if (r.pos != text.length()) {
            throw r.error("unexpected text after the value");
        }
        return v;
    }

    /** {@code s} as a JSON string literal, quotes included. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        quote(sb, s);
        return sb.toString();
    }

    /** Appends {@code s} as a JSON string literal, quotes included. */
    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        hex(sb, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        } else {
                            hex(sb, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        hex(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void hex(StringBuilder sb, char c) {
        sb.append("\\u");
        String h = Integer.toHexString(c);
        for (int k = h.length(); k < 4; k++) {
            sb.append('0');
        }
        sb.append(h);
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        JsonException error(String what) {
            return new JsonException(what, pos);
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        JsonValue value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH);
            }
            if (pos >= s.length()) {
                throw error("expected a value, found the end of the text");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new JsonValue.Str(string());
                case 't' -> literal("true", JsonValue.Bool.TRUE);
                case 'f' -> literal("false", JsonValue.Bool.FALSE);
                case 'n' -> literal("null", JsonValue.Null.NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        JsonValue literal(String word, JsonValue v) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return v;
        }

        JsonValue object(int depth) {
            pos++; // {
            LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return JsonValue.Obj.EMPTY;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a member name in quotes");
                }
                String key = string();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("expected ':' after a member name");
                }
                pos++;
                skipWhitespace();
                members.put(key, value(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return new JsonValue.Obj(members);
                } else {
                    throw error("expected ',' or '}' in an object");
                }
            }
        }

        JsonValue array(int depth) {
            pos++; // [
            List<JsonValue> items = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return JsonValue.Arr.EMPTY;
            }
            while (true) {
                skipWhitespace();
                items.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return new JsonValue.Arr(items);
                } else {
                    throw error("expected ',' or ']' in an array");
                }
            }
        }

        /** The character at the cursor, or 0 at the end (0 is never valid where peek is used). */
        char peek() {
            return pos < s.length() ? s.charAt(pos) : 0;
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return out;
                }
                if (c < 0x20) {
                    throw error("raw control character in a string");
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, start, pos);
                    pos++;
                    if (pos >= s.length()) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 >= s.length()) {
                                throw error("short \\u escape");
                            }
                            int code = 0;
                            for (int k = 1; k <= 4; k++) {
                                int d = Character.digit(s.charAt(pos + k), 16);
                                if (d < 0) {
                                    throw error("bad hex digit in \\u escape");
                                }
                                code = code * 16 + d;
                            }
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw error("unknown escape \\" + e);
                    }
                    pos++;
                    start = pos;
                } else {
                    pos++;
                }
            }
        }

        JsonValue number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            char c = peek();
            if (c == '0') {
                pos++;
            } else if (c >= '1' && c <= '9') {
                digits();
            } else {
                throw error("expected a digit");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw error("expected a digit after '.'");
                }
                digits();
            }
            c = peek();
            if (c == 'e' || c == 'E') {
                pos++;
                c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("expected a digit in the exponent");
                }
                digits();
            }
            if (isDigit(peek())) {
                throw error("leading zero in a number");
            }
            return new JsonValue.Num(s.substring(start, pos));
        }

        void digits() {
            while (isDigit(peek())) {
                pos++;
            }
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
