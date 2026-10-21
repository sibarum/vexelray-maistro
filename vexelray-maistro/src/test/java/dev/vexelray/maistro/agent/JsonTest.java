package dev.vexelray.maistro.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void readsEveryKindOfValue() {
        JsonValue v = Json.parse(" {\"s\":\"x\",\"i\":-12,\"f\":1.5e2,\"t\":true,\"n\":null,\"a\":[1,\"two\",[]],\"o\":{}} ");
        assertEquals("x", v.get("s").asString());
        assertEquals(-12, v.get("i").asLong(0));
        assertEquals(150.0, v.get("f").asDouble(0));
        assertEquals(150, v.get("f").asLong(0));
        assertTrue(v.get("t").asBool(false));
        assertTrue(v.get("n").isNull());
        assertTrue(v.has("n"));
        assertEquals(3, v.get("a").size());
        assertEquals("two", v.get("a").get(1).asString());
        assertTrue(v.get("o").isObject());
        assertEquals(List.of("s", "i", "f", "t", "n", "a", "o"), List.copyOf(((JsonValue.Obj) v).keys()));
    }

    @Test
    void missingThingsAnswerDefaultsInsteadOfThrowing() {
        JsonValue v = Json.parse("{\"a\":\"text\",\"n\":5}");
        assertSame(JsonValue.Null.NULL, v.get("nope"));
        assertEquals("", v.get("nope").get("deeper").get(3).asString());
        assertEquals("fallback", v.get("n").asString("fallback"));
        assertEquals(7, v.get("a").asLong(7));
        assertFalse(v.get("a").asBool(false));
        assertFalse(v.has("nope"));
        assertEquals(0, v.get("a").size());
        assertTrue(v.get("a").items().isEmpty());
        assertSame(JsonValue.Null.NULL, Json.parse("[1]").get(5));
        assertSame(JsonValue.Null.NULL, Json.parse("[1]").get(-1));
    }

    @Test
    void decodesEscapesAndSurrogatePairs() {
        JsonValue v = Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u0041\\ud83d\\ude00\\u00e9\"");
        assertEquals("a\"b\\c/d\b\f\n\r\tA\uD83D\uDE00\u00e9", v.asString());
        assertEquals(0x1F600, v.asString().codePointAt(v.asString().indexOf('\uD83D')));
    }

    @Test
    void writesEscapesAndKeepsNonAscii() {
        String s = "q\"b\\n\n\t\u0001 \u00e9 \uD83D\uDE00";
        assertEquals("\"q\\\"b\\\\n\\n\\t\\u0001 \u00e9 \uD83D\uDE00\"", Json.quote(s));
        // a lone surrogate is escaped, so the text stays valid once encoded as UTF-8
        assertEquals("\"\\ud83d!\"", Json.quote("\uD83D!"));
        assertEquals("\"\\ude00\"", Json.quote("\uDE00"));
    }

    @Test
    void roundTrips() {
        String[] texts = {
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Say hi\"}}",
                "[1,-0.5,2.5E-7,true,false,null,\"\",{},[]]",
                "{\"k\":\"line\\nbreak \\\"quoted\\\" \\u0000 \u00e9\"}",
                "0", "-0", "\"\"", "null",
        };
        for (String t : texts) {
            JsonValue v = Json.parse(t);
            assertEquals(v, Json.parse(v.toJson()), t);
        }
        assertEquals("{\"a\":[1,2],\"b\":null}", Json.parse(" { \"a\" : [ 1 , 2 ] , \"b\" : null } ").toJson());
    }

    @Test
    void numbersKeepTheirLiteral() {
        assertEquals("12345678901234567890", Json.parse("12345678901234567890").toJson());
        assertEquals(1, Json.parse("1.9").asLong(0));
        assertEquals("3", JsonValue.Num.of(3.0).literal());
        assertEquals("0.25", JsonValue.Num.of(0.25).literal());
        assertEquals("0", JsonValue.Num.of(Double.NaN).literal());
    }

    @Test
    void builderMakesObjectsInOrder() {
        JsonValue o = JsonValue.Obj.builder().put("type", "user").put("n", 3).put("ok", true)
                .put("nothing", (String) null).put("list", JsonValue.Arr.ofStrings(List.of("a", "b"))).build();
        assertEquals("{\"type\":\"user\",\"n\":3,\"ok\":true,\"nothing\":null,\"list\":[\"a\",\"b\"]}", o.toJson());
    }

    @Test
    void laterDuplicateKeyWins() {
        assertEquals(2, Json.parse("{\"a\":1,\"a\":2}").get("a").asLong(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "{", "}", "[1,]", "{\"a\":1,}", "{a:1}", "'x'", "01", "-", "1.", ".5", "1e", "+1",
            "\"unterminated", "\"bad \\x escape\"", "\"\\u12\"", "\"\\u12G4\"", "\"raw\ttab\"", "tru", "nul",
            "{} {}", "[1] x", "{\"a\" 1}", "[1 2]", "NaN", "Infinity", "// c\n1",
    })
    void rejectsInvalidText(String text) {
        assertThrows(JsonException.class, () -> Json.parse(text));
    }

    @Test
    void refusesHostileNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);
        JsonException e = assertThrows(JsonException.class, () -> Json.parse(deep));
        assertTrue(e.getMessage().contains("nested"));
        String ok = "[".repeat(100) + "]".repeat(100);
        assertEquals(ok, Json.parse(ok).toJson());
    }

    @Test
    void errorCarriesOffset() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("[1, x]"));
        assertEquals(4, e.offset());
    }
}
