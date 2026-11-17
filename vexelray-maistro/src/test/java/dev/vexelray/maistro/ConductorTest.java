package dev.vexelray.maistro;

import dev.vexelray.maistro.agent.AgentEvent;
import dev.vexelray.maistro.agent.Json;
import dev.vexelray.maistro.chat.Turn;
import dev.vexelray.maistro.tools.McpServer;
import dev.vexelray.maistro.tools.Toolset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message is queued on the caller's thread and delivered by the {@link Talk} job: saying something starts no
 * process, and the job's delivery is what finds (here, fails to find) the CLI.
 */
class ConductorTest {

    @TempDir
    Path home;

    private final List<Work.Deliver> deliveries = new ArrayList<>();
    private Model model;
    private Conductor conductor;

    @BeforeEach
    void setUp() {
        model = new Model();
        model.change(d -> d.withAccount(a -> new Doc.Account(Doc.Account.State.SIGNED_IN, "test", "", "")));
        Toolset tools = new Toolset(new McpServer("maistro", "test", List.of()), home.resolve("mcp.json"),
                List.of());
        conductor = new Conductor(model, new Conductor.Launch(home.resolve("no-claude.exe").toString(), "", "", ""),
                tools, Sender.collecting(Work.Deliver.TOPIC, deliveries));
    }

    @AfterEach
    void tearDown() {
        conductor.close();
    }

    private List<String> transcript() {
        return model.doc().strand(Doc.MAIN).orElseThrow().transcript().turns().stream().map(Turn::text).toList();
    }

    private boolean saysNotFound() {
        return transcript().stream().anyMatch(t -> t.startsWith("Claude Code was not found"));
    }

    @Test
    void sayingSomethingStartsNothingAndAsksForOneDelivery() {
        conductor.send("one");
        conductor.send("two");
        assertEquals(List.of(new Work.Deliver(Doc.MAIN)), deliveries);
        assertEquals(List.of("one", "two"), transcript());
        assertFalse(saysNotFound(), "nothing looked for the CLI on the caller's thread");
    }

    @Test
    void theDeliveryIsWhatLooksForTheCli() {
        conductor.send("one");
        conductor.deliver(deliveries.getFirst().strand());
        assertTrue(saysNotFound());
    }

    @Test
    void aMessageAfterADeliveryAsksForAnother() {
        conductor.send("one");
        conductor.deliver(Doc.MAIN);
        conductor.send("two");
        assertEquals(2, deliveries.size());
    }

    /** As the CLI ends a turn it could not take for want of a sign-in (real-not-logged-in.jsonl). */
    private void notLoggedIn() {
        String why = "Not logged in · Please run /login";
        conductor.main().onApiError(new AgentEvent.ApiError(Conductor.AUTHENTICATION_FAILED, why));
        conductor.main().onResult(new AgentEvent.Result(false, "success", why, "s-1", 0, 0, 0, 1,
                new AgentEvent.Usage(0, 0, 0, 0), "", Json.parse("{}")));
    }

    private Turn human(String text) {
        return model.doc().strand(Doc.MAIN).orElseThrow().transcript().turns().stream()
                .filter(t -> t.who() == Turn.Who.HUMAN && t.text().equals(text)).findFirst().orElseThrow();
    }

    @Test
    void losingTheSignInMidSessionShowsTheCardAndKeepsTheMessage() {
        conductor.send("hello");
        conductor.main().wrote("hello");
        notLoggedIn();
        Doc d = model.doc();
        assertEquals(Doc.Account.State.SIGNED_OUT, d.account().state());
        assertTrue(d.account().state().card());
        assertTrue(human("hello").chips().contains(Doc.Account.HELD));
        assertEquals(Conductor.WAITING_FOR_SIGN_IN, d.strand(Doc.MAIN).orElseThrow().activity());
        assertEquals(List.of("hello"), transcript(), "no red notes: the card says it");
    }

    @Test
    void signingInAgainSendsTheKeptMessage() {
        conductor.send("hello");
        conductor.deliver(Doc.MAIN);           // the delivery is done with; here it finds no CLI
        conductor.main().wrote("hello");       // as if it had written it
        deliveries.clear();
        notLoggedIn();
        conductor.accountChecked(conductor.checkAccount().check(),
                new Doc.Account(Doc.Account.State.SIGNED_IN, "test", "", ""));
        assertEquals(List.of(new Work.Deliver(Doc.MAIN)), deliveries);
        assertFalse(human("hello").chips().contains(Doc.Account.HELD));
    }

    @Test
    void theMessageKeptIsTheOneThatFailedNotTheLastSaid() {
        conductor.send("one");
        conductor.main().wrote("one");
        conductor.send("two");
        conductor.main().wrote("two");
        notLoggedIn();
        assertTrue(human("one").chips().contains(Doc.Account.HELD));
        assertFalse(human("two").chips().contains(Doc.Account.HELD));
    }

    @Test
    void anotherErrorIsStillANote() {
        conductor.main().onApiError(new AgentEvent.ApiError("rate_limit", "Slow down"));
        assertEquals(Doc.Account.State.SIGNED_IN, model.doc().account().state());
        assertEquals(List.of("Slow down"), transcript());
    }

    @Test
    void aClosedLaneDeliversNothing() {
        conductor.send("one");
        conductor.close();
        conductor.deliver(Doc.MAIN);
        assertFalse(saysNotFound());
    }
}
