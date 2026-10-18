package dev.vexelray.maistro.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shapes are the Agent SDK's documented {@code PermissionUpdate}s; not yet checked against a recorded real one. */
class SuggestionTest {

    private static Suggestion one(String json) {
        return Suggestion.describe(Json.parse(json)).orElseThrow();
    }

    @Test
    void aRuleSaysWhatItAllowsAndWhere() {
        Suggestion s = one("{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"localSettings\","
                + "\"rules\":[{\"toolName\":\"Bash\",\"ruleContent\":\"npm test:*\"}]}");
        assertEquals("Always allow Bash(npm test:*) · in this project, for me", s.label());
        assertTrue(s.persistent());

        Suggestion session = one("{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"session\","
                + "\"rules\":[{\"toolName\":\"Edit\"},{\"toolName\":\"Write\"}]}");
        assertEquals("Always allow Edit, Write · until Claude Code restarts", session.label());
        assertFalse(session.persistent());
    }

    @Test
    void everyScopeIsSaid() {
        String rule = "{\"type\":\"addRules\",\"behavior\":\"allow\",\"rules\":[{\"toolName\":\"Read\"}],"
                + "\"destination\":\"%s\"}";
        assertEquals("in this project, for everyone", one(rule.formatted("projectSettings")).scope());
        assertEquals("everywhere, for me", one(rule.formatted("userSettings")).scope());
    }

    @Test
    void aModeAndDirectoriesAreSaid() {
        assertEquals("Switch to acceptEdits",
                one("{\"type\":\"setMode\",\"mode\":\"acceptEdits\",\"destination\":\"session\"}").what());
        assertEquals("Allow working in C:/x, C:/y", one("{\"type\":\"addDirectories\","
                + "\"directories\":[\"C:/x\",\"C:/y\"],\"destination\":\"session\"}").what());
    }

    @Test
    void whatCannotBeSaidIsNotOffered() {
        List<Suggestion> offered = Suggestion.offered(Json.parse("["
                + "{\"type\":\"addRules\",\"behavior\":\"deny\",\"destination\":\"session\","
                + "\"rules\":[{\"toolName\":\"Bash\"}]},"
                + "{\"type\":\"removeRules\",\"behavior\":\"allow\",\"destination\":\"session\","
                + "\"rules\":[{\"toolName\":\"Bash\"}]},"
                + "{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"somewhere\","
                + "\"rules\":[{\"toolName\":\"Bash\"}]},"
                + "{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"session\",\"rules\":[{}]},"
                + "{\"type\":\"setMode\",\"destination\":\"session\"},"
                + "{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"session\","
                + "\"rules\":[{\"toolName\":\"Read\"}]}]"));
        assertEquals(1, offered.size(), "only the last one can be put into words");
        assertEquals("Always allow Read", offered.getFirst().what());
        assertTrue(Suggestion.offered(Json.parse("null")).isEmpty());
    }

    @Test
    void theUpdateGoesBackAsItCame() {
        JsonValue raw = Json.parse("{\"type\":\"addRules\",\"behavior\":\"allow\",\"destination\":\"session\","
                + "\"rules\":[{\"toolName\":\"Bash\",\"ruleContent\":\"ls\"}],\"extra\":{\"kept\":true}}");
        assertSame(raw, Suggestion.describe(raw).orElseThrow().update());
    }
}
