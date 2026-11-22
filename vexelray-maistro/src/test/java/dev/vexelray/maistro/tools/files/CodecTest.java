package dev.vexelray.maistro.tools.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vexelray.maistro.tools.files.Codec.Encoding;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CodecTest {

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aMarkSaysTheEncoding() {
        assertEquals(Optional.of(Encoding.UTF_8_BOM), Codec.detect(concat(bytes(0xEF, 0xBB, 0xBF),
                "hi".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Optional.of(Encoding.UTF_16LE_BOM), Codec.detect(bytes(0xFF, 0xFE, 'h', 0, 'i', 0)));
        assertEquals(Optional.of(Encoding.UTF_16BE_BOM), Codec.detect(bytes(0xFE, 0xFF, 0, 'h', 0, 'i')));
        assertEquals(Optional.empty(), Codec.detect(bytes(0xFF, 0xFE, 0, 0, 'h', 0, 0, 0)), "UTF-32 is not read");
    }

    @Test
    void withoutAMarkOnlyValidUtf8IsText() {
        assertEquals(Optional.of(Encoding.UTF_8), Codec.detect("naïve".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.of(Encoding.UTF_8), Codec.detect(new byte[0]));
        assertEquals(Optional.empty(), Codec.detect("naïve".getBytes(StandardCharsets.ISO_8859_1)),
                "Latin-1 is not guessed");
        assertEquals(Optional.empty(), Codec.detect(bytes('h', 0, 'i', 0)), "UTF-16 without a mark is not guessed");
        assertEquals(Optional.empty(), Codec.detect(bytes('a', 'b', 0, 'c')), "a NUL near the start is binary");
    }

    @Test
    void aBadByteIsAnErrorAtItsOffsetNeverAReplacement() {
        byte[] b = concat("abc".getBytes(StandardCharsets.UTF_8), bytes(0xC3, 0x28));
        Codec.Decoded d = Codec.decode(b, Encoding.UTF_8);
        assertFalse(d.ok());
        assertEquals(3, d.offset());
        assertTrue(d.problem().contains("byte 3 (0xc3)"), d.problem());
    }

    @Test
    void theOffsetCountsTheMark() {
        byte[] b = concat(bytes(0xEF, 0xBB, 0xBF, 'a'), bytes(0xFF));
        assertEquals(4, Codec.decode(b, Encoding.UTF_8_BOM).offset());
    }

    @Test
    void aMarkedEncodingWantsItsMark() {
        assertFalse(Codec.decode("hi".getBytes(StandardCharsets.UTF_8), Encoding.UTF_8_BOM).ok());
        Codec.Decoded unmarked = Codec.decode(bytes(0xEF, 0xBB, 0xBF, 'h'), Encoding.UTF_8);
        assertEquals("﻿h", unmarked.text(), "read as plain UTF-8, the mark is a character");
    }

    @Test
    void textComesOutAsItWentInAndBackAgain() throws Exception {
        String nfd = Normalizer.normalize("résumé", Normalizer.Form.NFD);
        for (Encoding e : new Encoding[]{Encoding.UTF_8, Encoding.UTF_8_BOM, Encoding.UTF_16LE_BOM,
                Encoding.UTF_16BE_BOM, Encoding.UTF_16LE}) {
            byte[] b = Codec.encode(nfd + "\r\n😀", e);
            assertEquals(nfd + "\r\n😀", Codec.decode(b, e).text(), e.label() + ": nothing normalized");
            assertArrayEquals(b, Codec.encode(Codec.decode(b, e).text(), e));
        }
        assertEquals("ÿ", Codec.decode(bytes(0xFF), Encoding.LATIN_1).text());
    }

    @Test
    void anEncodingIsNamedByItsLabel() {
        assertEquals(Optional.of(Encoding.UTF_16LE_BOM), Encoding.named("UTF-16LE-BOM"));
        assertEquals(Optional.empty(), Encoding.named("cp1252"));
        assertTrue(Encoding.labels().startsWith("utf-8, utf-8-bom"));
    }

    @Test
    void theEndingsAreCountedAndNamed() {
        assertEquals("lf", Codec.endings("a\nb\n").style());
        assertEquals("crlf", Codec.endings("a\r\nb").style());
        assertEquals("cr", Codec.endings("a\rb\r").style());
        assertEquals("mixed", Codec.endings("a\r\nb\nc\rd").style());
        assertEquals("none", Codec.endings("one line").style());
        Codec.Endings m = Codec.endings("a\r\nb\nc\rd");
        assertEquals(1, m.lf());
        assertEquals(1, m.crlf());
        assertEquals(1, m.cr());
        assertFalse(m.finalNewline());
        assertEquals(4, m.lines(false));
        assertEquals(2, Codec.endings("a\nb\n").lines(false));
        assertEquals(0, Codec.endings("").lines(true));
    }

    @Test
    void sha256IsLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Codec.sha256(new byte[0]));
    }
}
