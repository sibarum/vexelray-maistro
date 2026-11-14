package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.archive.ConversationCodec;
import dev.vexelray.maistro.archive.Summary;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;
import sibarum.atchung.Sender;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every conversation, kept: one JSON file per conversation ({@link ConversationCodec}) in a folder of the
 * application's home, so a later slice can list them and resume one.
 *
 * <h2>When it writes</h2>
 *
 * <p>On the model's listener, whenever the session is idle and what would be saved differs from what was last
 * saved: so once each time a turn ends, and never while a reply streams in. A conversation with nothing said is not
 * saved. Nothing is written until {@link #arm} — which {@link Screen} does once the window is open, so a tree built
 * headless for a test writes nothing — and nothing at all on the listener's thread: the snapshot is encoded there
 * and sent to the {@link WriteConversation} job. The one exception is {@link #close}, which the framework calls as the
 * application ends and which writes the session as it stands, turn or no turn, on its own thread.
 *
 * <h2>Order</h2>
 *
 * <p>The job lane keeps no order between two requests, so one write is out at a time: the next snapshot waits for
 * the last one's {@link Work.Written}, and only the newest waiting is sent. Each snapshot is numbered as it is handed
 * over, and a write checks that nothing newer has been written to its file, which keeps {@link #close}'s own write
 * from being overwritten by one still out on the lane. The file is written beside its final name and moved over it,
 * so a crash leaves the old file or the new one, never half of one.
 */
final class Archive implements AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.archive");

    /** Snapshot numbers. Process-wide, as the files are: two archives over one folder still agree which is newer. */
    private static final AtomicLong HANDED = new AtomicLong();
    /** The newest snapshot number written, per file. */
    private static final Map<Path, Long> WRITTEN = new ConcurrentHashMap<>();

    private final Path dir;
    private final Sender<Work.Write> writes;
    private final Sender<Work.ListAll> lists;
    private final Sender<Work.Read> reads;

    private volatile boolean armed;
    /** What was last handed over, to tell a change worth saving from a pane toggle. Guarded by this. */
    private Saved last;
    /** The newest session seen, busy or not: what {@link #close} saves. */
    private volatile Doc latest;
    /** Whether a write is out on the job lane. Guarded by this. */
    private boolean out;
    /** The write out on the job lane, or null. Guarded by this. */
    private Work.Write sent;
    /** The newest snapshot waiting for the one out to come back, or null. Guarded by this. */
    private Work.Write next;

    /** What decides whether a session needs saving again; everything in a {@link Conversation} but the times. */
    private record Saved(Doc.Meta meta, List<Doc.Strand> strands, MapGraph graph, Map<String, Layout.Point> positions) {

        static Saved of(Doc d) {
            return new Saved(d.meta(), d.strands(), d.mind().graph(), d.mind().positions());
        }
    }

    Archive(Path dir, Sender<Work.Write> writes, Sender<Work.ListAll> lists, Sender<Work.Read> reads) {
        this.dir = dir;
        this.writes = writes;
        this.lists = lists;
        this.reads = reads;
    }

    /** The folder the files go in. */
    Path dir() {
        return dir;
    }

    /** Start saving. */
    void arm() {
        armed = true;
    }

    /** The model's listener: send the session to be written if a turn has ended with something new in it. */
    void changed(Doc d) {
        if (!armed) {
            return;
        }
        latest = d;
        if (d.anyBusy()) {
            return;
        }
        Work.Write w = handOver(d);
        if (w == null) {
            return;
        }
        synchronized (this) {
            if (out) {
                next = w;
                return;
            }
            out = true;
            sent = w;
        }
        writes.send(w);
    }

    /** A write came back: send the one that waited for it, if any. */
    void written(Work.Written w) {
        if (!w.problem().isEmpty()) {
            LOG.warn("could not save conversation {}: {}", w.id(), w.problem());
        }
        Work.Write then;
        synchronized (this) {
            then = next;
            next = null;
            out = then != null;
            sent = then;
        }
        if (then != null) {
            writes.send(then);
        }
    }

    /**
     * The application is closing: save the session as it is now, on this thread, even mid-turn, since there is no
     * later. What was streaming loads as finished, and an ask nobody answered as lapsed.
     */
    @Override
    public void close() {
        Doc d = latest;
        if (!armed || d == null) {
            return;
        }
        Work.Write w = handOver(d);
        synchronized (this) {
            // Nothing new since the last hand-over: it may still be waiting, or out on a lane that is stopping.
            if (w == null) {
                w = next != null ? next : out ? sent : null;
            }
        }
        if (w != null) {
            write(w);
        }
    }

    /** Number {@code d} for writing and return it encoded, or null if it is empty or nothing changed. */
    private synchronized Work.Write handOver(Doc d) {
        if (d.strands().stream().allMatch(s -> s.transcript().size() == 0)) {
            return null;
        }
        Saved now = Saved.of(d);
        if (Objects.equals(now, last)) {
            return null;
        }
        last = now;
        return new Work.Write(dir, d.meta().id(), HANDED.incrementAndGet(), ConversationCodec.encode(conversation(d)));
    }

    /** The session as it is kept. */
    static Conversation conversation(Doc d) {
        List<Conversation.Branch> branches = new ArrayList<>();
        for (Doc.Strand s : d.strands()) {
            if (!s.isMain()) {
                branches.add(new Conversation.Branch(s.id(), s.from(), s.label(), agent(s.agent()), s.transcript(),
                        s.graph()));
            }
        }
        Doc.Strand main = d.main();
        return new Conversation(d.meta().id(), d.meta().title(), d.meta().started(), System.currentTimeMillis(),
                agent(main.agent()), main.transcript(), d.mind().graph(), d.mind().positions(), d.mind().turn(),
                branches, main.news());
    }

    private static Conversation.Agent agent(Doc.AgentInfo a) {
        return new Conversation.Agent(a.sessionId(), a.model(), a.cwd(), a.costUsd(), a.turns(), a.inputTokens(),
                a.outputTokens());
    }

    /** Ask for every saved conversation; the {@link ListConversations} job answers with {@link Work.Listed}. */
    void list() {
        lists.send(new Work.ListAll(dir));
    }

    /** Ask for conversation {@code id}; the {@link ReadConversation} job answers with {@link Work.Loaded}. */
    void load(String id) {
        reads.send(new Work.Read(dir, id));
    }

    /** The file conversation {@code id} is kept in. */
    Path file(String id) {
        return file(dir, id);
    }

    private static Path file(Path dir, String id) {
        return dir.resolve(id + ".json");
    }

    // ------------------------------------------------------------------ the disk: the jobs', and close's

    /** Every conversation in {@code dir}, newest first. A file that does not read is left out, and logged. */
    static List<Summary> summaries(Path dir) {
        List<Summary> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try {
                    out.add(Summary.of(ConversationCodec.decode(Files.readString(f, StandardCharsets.UTF_8))));
                } catch (IOException | IllegalArgumentException e) {
                    LOG.warn("could not read conversation {}", f, e);
                }
            }
        } catch (IOException e) {
            LOG.warn("could not list conversations in {}", dir, e);
        }
        out.sort(Summary.NEWEST_FIRST);
        return out;
    }

    /** {@link #summaries(Path)} of this archive's folder. */
    List<Summary> summaries() {
        return summaries(dir);
    }

    /** Conversation {@code r.id()}'s text, or why it could not be read. */
    static Work.Loaded read(Work.Read r) {
        Path f = file(r.dir(), r.id());
        try {
            return new Work.Loaded(r.id(), Files.readString(f, StandardCharsets.UTF_8), "");
        } catch (IOException e) {
            LOG.warn("could not read conversation {}", f, e);
            return new Work.Loaded(r.id(), "", "Could not open that conversation: " + e.getMessage());
        }
    }

    /** Write {@code w}, unless something newer has been written to its file already. */
    static synchronized Work.Written write(Work.Write w) {
        Path dir = w.dir();
        Path target = file(dir, w.id());
        if (WRITTEN.getOrDefault(target, 0L) > w.number()) {
            return new Work.Written(w.id(), w.number(), "");
        }
        Path temp = dir.resolve(w.id() + ".json.tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(temp, w.json(), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            WRITTEN.put(target, w.number());
            LOG.debug("saved conversation {} to {}", w.id(), target);
            return new Work.Written(w.id(), w.number(), "");
        } catch (IOException e) {
            return new Work.Written(w.id(), w.number(), target + ": " + e);
        }
    }
}
