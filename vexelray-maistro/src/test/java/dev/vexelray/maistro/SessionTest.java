package dev.vexelray.maistro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTest {

    @Test
    void thePanesRoundTrip() {
        for (int bits = 0; bits < 8; bits++) {
            Doc.Panes p = new Doc.Panes((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
            assertEquals(p, Session.decode(Session.encode(p)));
        }
    }

    @Test
    void somethingMalformedIsEveryPaneUp() {
        assertEquals(Doc.Panes.ALL, Session.decode("bogus"));
        assertEquals(Doc.Panes.ALL, Session.decode(null));
    }

    @Test
    void togglingAPaneFlipsOnlyThatPane() {
        Doc.Panes p = Doc.Panes.ALL.toggle(Doc.Pane.MAP);
        assertEquals(new Doc.Panes(true, false, true), p);
        assertEquals(Doc.Panes.ALL, p.toggle(Doc.Pane.MAP));
    }
}
