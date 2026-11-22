package dev.vexelray.maistro.tools.files;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Bytes as text, only when the encoding is known: detected from the bytes and reported, or stated by the agent; and
 * decoded strictly (agent-tools.md, principle 3).
 *
 * <p><b>Detection guesses nothing.</b> A byte-order mark says UTF-8, UTF-16LE or UTF-16BE. Without one, the bytes are
 * UTF-8 if they decode as UTF-8 and hold no NUL in their first {@link #SNIFF} bytes (git's test for binary). Anything
 * else is not detected as text: UTF-16 without a mark, Latin-1 or a binary file can each be read only by naming the
 * encoding ({@link Encoding#named}).
 *
 * <p><b>Decoding replaces nothing.</b> A byte that is not valid in the encoding is an error naming its offset in the
 * file, never U+FFFD. Unicode is not normalized: NFC and NFD come out as they went in.
 */
public final class Codec {

    /** How much of a file is looked at for a NUL before it is taken for text, as git does. */
    public static final int SNIFF = 8000;

    private Codec() {
    }

    /** The encodings the tools read and name, each with whether the file carries a byte-order mark. */
    public enum Encoding {
        UTF_8("utf-8", StandardCharsets.UTF_8, new byte[0]),
        UTF_8_BOM("utf-8-bom", StandardCharsets.UTF_8, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}),
        UTF_16LE("utf-16le", StandardCharsets.UTF_16LE, new byte[0]),
        UTF_16LE_BOM("utf-16le-bom", StandardCharsets.UTF_16LE, new byte[]{(byte) 0xFF, (byte) 0xFE}),
        UTF_16BE("utf-16be", StandardCharsets.UTF_16BE, new byte[0]),
        UTF_16BE_BOM("utf-16be-bom", StandardCharsets.UTF_16BE, new byte[]{(byte) 0xFE, (byte) 0xFF}),
        /** Every byte one character: decodes anything, so it is only ever asked for, never detected. */
        LATIN_1("latin-1", StandardCharsets.ISO_8859_1, new byte[0]);

        private final String label;
        private final Charset charset;
        private final byte[] bom;

        Encoding(String label, Charset charset, byte[] bom) {
            this.label = label;
            this.charset = charset;
            this.bom = bom;
        }

        /** The name the tools say and take: {@code utf-8}, {@code utf-8-bom}, {@code utf-16le}, ... */
        public String label() {
            return label;
        }

        /** How many bytes the mark takes at the start of the file; 0 for an encoding without one. */
        public int bomLength() {
            return bom.length;
        }

        /** The encoding called {@code label}, as {@link #label} says them, ignoring case. */
        public static Optional<Encoding> named(String label) {
            for (Encoding e : values()) {
                if (e.label.equalsIgnoreCase(label)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }

        /** Every label, for an error that says what may be asked for. */
        public static String labels() {
            StringBuilder b = new StringBuilder();
            for (Encoding e : values()) {
                b.append(b.isEmpty() ? "" : ", ").append(e.label);
            }
            return b.toString();
        }

        boolean markedIn(byte[] bytes) {
            if (bom.length == 0 || bytes.length < bom.length) {
                return false;
            }
            for (int i = 0; i < bom.length; i++) {
                if (bytes[i] != bom[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The encoding {@code bytes} are in, if it can be told without guessing: a byte-order mark, or valid UTF-8 with no
     * NUL near the start. Empty for anything else, which is not text until an encoding is named.
     */
    public static Optional<Encoding> detect(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE && bytes[2] == 0 && bytes[3] == 0) {
            return Optional.empty(); // UTF-32LE's mark, which begins as UTF-16LE's does; not read here
        }
        for (Encoding e : new Encoding[]{Encoding.UTF_8_BOM, Encoding.UTF_16LE_BOM, Encoding.UTF_16BE_BOM}) {
            if (e.markedIn(bytes)) {
                return Optional.of(e);
            }
        }
        int sniff = Math.min(bytes.length, SNIFF);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                return Optional.empty();
            }
        }
        return decode(bytes, Encoding.UTF_8).ok() ? Optional.of(Encoding.UTF_8) : Optional.empty();
    }

    /**
     * Bytes decoded.
     *
     * @param text    the text, the byte-order mark not part of it; null when the bytes did not decode
     * @param offset  where in the bytes decoding failed; -1 when it did not
     * @param problem why it failed, naming the offset; empty when it did not
     */
    public record Decoded(String text, long offset, String problem) {

        public boolean ok() {
            return text != null;
        }
    }

    /**
     * {@code bytes} as {@code encoding}, strictly. An encoding with a mark wants it at the start; one without takes the
     * bytes as they are, so a mark there is the character U+FEFF, as it is to any program reading it that way.
     */
    public static Decoded decode(byte[] bytes, Encoding encoding) {
        int skip = 0;
        if (encoding.bomLength() > 0) {
            if (!encoding.markedIn(bytes)) {
                return new Decoded(null, 0, "the file does not start with " + encoding.label + "'s byte-order mark");
            }
            skip = encoding.bomLength();
        }
        CharsetDecoder d = encoding.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, skip, bytes.length - skip);
        CharBuffer out = CharBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 8,
                (long) Math.ceil((bytes.length - skip) * (double) d.maxCharsPerByte()) + 1));
        CoderResult r = d.decode(in, out, true);
        if (!r.isError()) {
            r = d.flush(out);
        }
        if (r.isError()) {
            int at = in.position();
            return new Decoded(null, at, "byte " + at + " (0x" + HexFormat.of().toHexDigits(bytes[Math.min(at,
                    bytes.length - 1)]) + ") is not valid " + encoding.label);
        }
        out.flip();
        return new Decoded(out.toString(), -1, "");
    }

    /** {@code text} in {@code encoding}, with its mark if it has one; strictly, so nothing is replaced. */
    public static byte[] encode(String text, Encoding encoding) throws CharacterCodingException {
        ByteBuffer body = encoding.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        byte[] out = new byte[encoding.bomLength() + body.remaining()];
        System.arraycopy(encoding.bom, 0, out, 0, encoding.bomLength());
        body.get(out, encoding.bomLength(), body.remaining());
        return out;
    }

    /**
     * How a text's lines end: a count of each kind, whether the last line has an ending, and the style that names
     * them all ({@code lf}, {@code crlf}, {@code cr}, {@code mixed}, or {@code none} for a text of one line with no
     * ending).
     */
    public record Endings(long lf, long crlf, long cr, boolean finalNewline) {

        public String style() {
            int kinds = (lf > 0 ? 1 : 0) + (crlf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
            if (kinds == 0) {
                return "none";
            }
            if (kinds > 1) {
                return "mixed";
            }
            return lf > 0 ? "lf" : crlf > 0 ? "crlf" : "cr";
        }

        /** The lines there are: one more than the endings, unless the last ending closes the text. */
        public long lines(boolean empty) {
            long endings = lf + crlf + cr;
            return empty ? 0 : finalNewline ? endings : endings + 1;
        }
    }

    /** The line-ending census of {@code text}. */
    public static Endings endings(CharSequence text) {
        long lf = 0;
        long crlf = 0;
        long cr = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < n && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        boolean last = n > 0 && (text.charAt(n - 1) == '\n' || text.charAt(n - 1) == '\r');
        return new Endings(lf, crlf, cr, last);
    }

    /** The sha256 of {@code bytes}, as lowercase hex. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }
}
