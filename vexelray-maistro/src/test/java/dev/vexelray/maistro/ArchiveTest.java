package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.ConversationCodec;
import dev.vexelray.maistro.chat.Turn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive's rules for when it writes, with the job lane replaced by lists of requests the test runs by hand,
 * through the jobs themselves.
 */
class ArchiveTest {

    @TempDir
    Path home;

    private final List<Work.Write> writes = new ArrayList<>();
    private final List<Work.ListAll> lists = new ArrayList<>();
    private final List<Work.Read> reads = new ArrayList<>();

    private Archive archive(String folder) {
        return new Archive(home.resolve(folder), Sender.collecting(Work.Write.TOPIC, writes),
                Sender.collecting(Work.ListAll.TOPIC, lists), Sender.collecting(Work.Read.TOPIC, reads));
    }

    private Archive archive() {
        Archive a = archive("conversations");
        a.arm();
        return a;
    }

    /** Run every write sent so far, as the job would, and hand each reply back as the screen would. */
    private void writeAll(Archive a) {
        while (!writes.isEmpty()) {
            a.written(new WriteConversation().run(writes.removeFirst()));
        }
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
    void aTurnThatEndsIsSavedByTheJobAndOnlyThere() throws Exception {
        Archive a = archive();
        Doc d = said(Doc.initial(), "Fix the build");
        a.changed(d);
        assertFalse(Files.exists(a.file(d.meta().id())), "nothing is written on the listener's thread");
        writeAll(a);
        assertEquals("Fix the build", ConversationCodec.decode(saved(a, d)).title());
    }

    @Test
    void nothingIsSavedMidTurnEmptyUnchangedOrUnarmed() {
        Archive a = archive();
        a.changed(Doc.initial());
        Doc d = said(Doc.initial(), "hello");
        a.changed(busy(d));
        assertTrue(writes.isEmpty(), "an empty conversation, and one mid-turn, are not saved");
        a.changed(d);
        a.changed(d.withPanes(new Doc.Panes(true, false, true)));
        assertEquals(1, writes.size(), "a pane toggle is not a change worth saving");

        Archive unarmed = archive("other");
        unarmed.changed(said(Doc.initial(), "hi"));
        assertEquals(1, writes.size());
    }

    @Test
    void oneWriteIsOutAtATimeAndOnlyTheNewestWaits() throws Exception {
        Archive a = archive();
        Doc one = said(Doc.initial(), "one");
        Doc two = said(one, "two");
        Doc three = said(two, "three");
        a.changed(one);
        a.changed(two);
        a.changed(three);
        assertEquals(1, writes.size(), "the next waits for the reply to the one out");
        a.written(new WriteConversation().run(writes.removeFirst()));
        assertEquals(1, writes.size(), "then the newest that waited goes, and the one it replaced never does");
        writeAll(a);
        assertEquals(3, ConversationCodec.decode(saved(a, three)).transcript().size());
    }

    @Test
    void closingSavesEvenMidTurnOnItsOwnThread() throws Exception {
        Archive a = archive();
        Doc d = busy(said(Doc.initial(), "hello"));
        a.changed(d);
        assertTrue(writes.isEmpty());
        a.close();
        assertTrue(writes.isEmpty(), "written by close itself, not sent to a lane that is stopping");
        assertEquals("hello", ConversationCodec.decode(saved(a, d)).transcript().turns().getFirst().text());
    }

    @Test
    void theListIsNewestFirstAndSkipsWhatDoesNotRead() throws Exception {
        Archive a = archive();
        a.changed(said(Doc.initial(), "older"));
        writeAll(a);
        Thread.sleep(5);
        a.changed(said(Doc.initial(), "newer"));
        writeAll(a);
        Files.writeString(a.dir().resolve("broken.json"), "{not json", StandardCharsets.UTF_8);
        Files.writeString(a.dir().resolve("half.json.tmp"), "{", StandardCharsets.UTF_8);

        a.list();
        assertEquals(List.of(new Work.ListAll(a.dir())), lists, "read by the job, not on the caller's thread");
        Work.Listed got = new ListConversations().run(lists.getFirst());
        assertEquals(List.of("newer", "older"), got.rows().stream().map(s -> s.title()).toList());
        assertEquals(1, got.rows().getFirst().messages());
    }

    @Test
    void loadingHandsOverTheConversationOrSaysWhyNot() {
        Archive a = archive();
        Doc d = said(Doc.initial(), "hello");
        a.changed(d);
        writeAll(a);
        a.load(d.meta().id());
        a.load("no-such");
        assertEquals(2, reads.size());
        Work.Loaded found = new ReadConversation().run(reads.get(0));
        Work.Loaded missing = new ReadConversation().run(reads.get(1));
        assertEquals("", found.problem());
        assertEquals("hello", ConversationCodec.decode(found.json()).title());
        assertTrue(missing.problem().startsWith("Could not"), missing.problem());
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
        Work.Write older = writes.removeFirst();
        a.changed(second);
        assertTrue(writes.isEmpty(), "the second waits for the first's reply");
        a.close();
        assertEquals(2, ConversationCodec.decode(saved(a, second)).transcript().size(),
                "closing writes the snapshot that was waiting");
        Archive.write(older);
        assertEquals(2, ConversationCodec.decode(saved(a, second)).transcript().size(),
                "and the older write, landing after it, leaves it");
    }

    @Test
    void closingWritesTheSnapshotStillOutWhenNothingIsNewer() throws Exception {
        Archive a = archive();
        Doc d = said(Doc.initial(), "hello");
        a.changed(d);
        assertEquals(1, writes.size());
        a.close();
        assertEquals("hello", ConversationCodec.decode(saved(a, d)).title(), "the lane may never run it");
    }
}
