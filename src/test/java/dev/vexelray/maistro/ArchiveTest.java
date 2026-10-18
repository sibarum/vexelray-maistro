package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.ConversationCodec;
import dev.vexelray.maistro.chat.Turn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archive's rules for when it writes, with the offload lane replaced by a queue the test runs by hand. */
class ArchiveTest {

    @TempDir
    Path home;

    private final List<Runnable> lane = new ArrayList<>();

    private Archive archive() {
        Archive a = new Archive(home.resolve("conversations"), lane::add);
        a.arm();
        return a;
    }

    private static Doc said(Doc d, String text) {
        return d.withStrand(Doc.MAIN, s -> s.withTranscript(t -> t.append(id -> Turn.human(id, text))))
                .withMeta(m -> m.titled(text));
    }

    private static Doc busy(Doc d) {
        return d.withStrand(Doc.MAIN, s -> s.withActivity("thinking", true));
    }

    private String saved(Archive a, Doc d) throws Exception {
        return Files.readString(a.file(d.meta().id()), StandardCharsets.UTF_8);
    }

    @Test
    void aTurnThatEndsIsSavedOnTheLaneAndOnlyThere() throws Exception {
        Archive a = archive();
        Doc d = said(Doc.initial(), "Fix the build");
        a.changed(d);
        assertFalse(Files.exists(a.file(d.meta().id())), "nothing is written on the listener's thread");
        lane.forEach(Runnable::run);
        assertEquals("Fix the build", ConversationCodec.decode(saved(a, d)).title());
    }

    @Test
    void nothingIsSavedMidTurnEmptyUnchangedOrUnarmed() {
        Archive a = archive();
        a.changed(Doc.initial());
        Doc d = said(Doc.initial(), "hello");
        a.changed(busy(d));
        assertTrue(lane.isEmpty(), "an empty conversation, and one mid-turn, are not saved");
        a.changed(d);
        a.changed(d.withPanes(new Doc.Panes(true, false, true)));
        assertEquals(1, lane.size(), "a pane toggle is not a change worth saving");

        Archive unarmed = new Archive(home.resolve("other"), lane::add);
        unarmed.changed(said(Doc.initial(), "hi"));
        assertEquals(1, lane.size());
    }

    @Test
    void closingSavesEvenMidTurnOnItsOwnThread() throws Exception {
        Archive a = archive();
        Doc d = busy(said(Doc.initial(), "hello"));
        a.changed(d);
        assertTrue(lane.isEmpty());
        a.close();
        assertTrue(lane.isEmpty(), "written by close itself, not handed to a lane that is stopping");
        assertEquals("hello", ConversationCodec.decode(saved(a, d)).transcript().turns().getFirst().text());
    }

    @Test
    void theListIsNewestFirstAndSkipsWhatDoesNotRead() throws Exception {
        Archive a = archive();
        Doc older = said(Doc.initial(), "older");
        a.changed(older);
        lane.forEach(Runnable::run);
        lane.clear();
        Thread.sleep(5);
        Doc newer = said(Doc.initial(), "newer");
        a.changed(newer);
        lane.forEach(Runnable::run);
        lane.clear();
        Files.writeString(a.dir().resolve("broken.json"), "{not json", StandardCharsets.UTF_8);
        Files.writeString(a.dir().resolve("half.json.tmp"), "{", StandardCharsets.UTF_8);

        List<List<dev.vexelray.maistro.archive.Summary>> got = new ArrayList<>();
        a.list(got::add);
        assertTrue(got.isEmpty(), "read on the lane, not the caller's thread");
        lane.forEach(Runnable::run);
        assertEquals(List.of("newer", "older"), got.getFirst().stream().map(s -> s.title()).toList());
        assertEquals(1, got.getFirst().getFirst().messages());
    }

    @Test
    void loadingHandsOverTheConversationOrSaysWhyNot() {
        Archive a = archive();
        Doc d = said(Doc.initial(), "hello");
        a.changed(d);
        lane.forEach(Runnable::run);
        lane.clear();
        List<String> heard = new ArrayList<>();
        a.load(d.meta().id(), c -> heard.add("loaded " + c.title()), why -> heard.add("failed"));
        a.load("no-such", c -> heard.add("loaded"), why -> heard.add("failed: " + why.startsWith("Could not")));
        lane.forEach(Runnable::run);
        assertEquals(List.of("loaded hello", "failed: true"), heard);
    }

    @Test
    void anEmptyFolderIsAnEmptyList() {
        assertTrue(archive().summaries().isEmpty());
    }

    @Test
    void anOlderWriteLandingLateLeavesTheNewer() throws Exception {
        Archive a = archive();
        Doc first = said(Doc.initial(), "one");
        Doc second = said(first, "two");
        a.changed(first);
        a.changed(second);
        lane.get(1).run();
        lane.get(0).run();
        assertEquals(2, ConversationCodec.decode(saved(a, second)).transcript().size());
    }
}
