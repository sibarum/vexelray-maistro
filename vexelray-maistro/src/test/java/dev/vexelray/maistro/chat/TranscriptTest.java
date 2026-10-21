package dev.vexelray.maistro.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptTest {

    @Test
    void settlingLapsesAnAskNobodyAnswered() {
        Transcript t = Transcript.empty()
                .append(id -> Turn.tool(id, "Bash", "ls").withAsk(Turn.Ask.PENDING))
                .append(id -> Turn.tool(id, "Edit", "a.txt").withAsk(Turn.Ask.ALLOWED));
        assertEquals(1, t.asking().size());
        Transcript settled = t.settle();
        assertEquals(Turn.Ask.LAPSED, settled.turns().get(0).ask());
        assertEquals(Turn.Ask.ALLOWED, settled.turns().get(1).ask());
        assertTrue(settled.asking().isEmpty());
    }

    @Test
    void appendingGivesEachTurnTheNextId() {
        Transcript t = Transcript.empty()
                .append(id -> Turn.human(id, "hello"))
                .append(id -> Turn.agent(id, "", true));
        assertEquals(2, t.size());
        assertEquals(1, t.turns().get(0).id());
        assertEquals(2, t.turns().get(1).id());
        assertEquals(3, t.nextId());
    }

    @Test
    void aStreamedReplyGrowsInPlace() {
        Transcript t = Transcript.empty().append(id -> Turn.agent(id, "Hel", true));
        long id = t.liveReply().id();
        t = t.append(i -> Turn.tool(i, "Read", "a.txt"));
        t = t.change(id, r -> r.withText(r.text() + "lo"));
        assertEquals("Hello", t.turns().getFirst().text());
        assertEquals(id, t.liveReply().id());
    }

    @Test
    void settlingStopsEverythingThatIsStillGrowing() {
        Transcript t = Transcript.empty()
                .append(id -> Turn.agent(id, "partial", true))
                .append(id -> Turn.tool(id, "Bash", "ls"))
                .settle();
        assertNull(t.liveReply());
        assertTrue(t.turns().stream().noneMatch(Turn::live));
        assertFalse(t.turns().stream().anyMatch(Turn::failed), "settling is not failing");
    }

    @Test
    void aToolThatErredIsMarkedFailed() {
        Turn tool = Turn.tool(1, "Bash", "false").finished(true);
        assertTrue(tool.failed());
        assertFalse(tool.live());
        assertEquals("TOOL · Bash", tool.heading());
    }

    @Test
    void changingATurnThatIsNotThereChangesNothing() {
        Transcript t = Transcript.empty().append(id -> Turn.human(id, "x"));
        assertEquals(t, t.change(99, r -> r.withText("y")));
    }

    @Test
    void theTurnsCannotBeChangedFromOutside() {
        Transcript t = Transcript.empty().append(id -> Turn.human(id, "x"));
        List<Turn> turns = t.turns();
        try {
            turns.add(Turn.human(5, "y"));
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("the list should be immutable");
    }
}
