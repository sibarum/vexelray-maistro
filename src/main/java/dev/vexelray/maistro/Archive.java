package dev.vexelray.maistro;

import dev.vexelray.maistro.archive.Conversation;
import dev.vexelray.maistro.archive.ConversationCodec;
import dev.vexelray.maistro.archive.Summary;
import dev.vexelray.maistro.chat.Transcript;
import dev.vexelray.maistro.map.Layout;
import dev.vexelray.maistro.map.MapGraph;
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
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every conversation, kept: one JSON file per conversation ({@link ConversationCodec}) in a folder of the
 * application's home, so a later slice can list them and resume one.
 *
 * <h2>When it writes</h2>
 *
 * <p>On the model's listener, whenever the session is idle and what would be saved differs from what was last
 * saved: so once each time a turn ends, and never while a reply streams in. A conversation with nothing said is not
 * saved. Nothing is written until {@link #arm} — which the last recipe does, so a tree built headless for a test
 * writes nothing — and nothing at all on the listener's thread: the snapshot is handed to the offload lane. The one
 * exception is {@link #close}, which the framework calls as the application ends and which writes the session as it
 * stands, turn or no turn.
 *
 * <h2>Order</h2>
 *
 * <p>Each snapshot is numbered as it is handed over. The offload lane may run two writes of one conversation at
 * once, or in either order, so a write checks, under the lock, that it is still the newest handed over for its
 * conversation, and does nothing if not. The file is written beside its final name and moved over it, so a crash
 * leaves the old file or the new one, never half of one.
 */
final class Archive implements AutoCloseable {

    private static final Log LOG = Log.of("vexelray-maistro.archive");

    private final Path dir;
    private final Executor offload;
    private final AtomicLong handed = new AtomicLong();
    /** The newest snapshot number handed over, per conversation id. */
    private final Map<String, Long> newest = new ConcurrentHashMap<>();
    private final Object writing = new Object();

    private volatile boolean armed;
    /** What was last handed over, to tell a change worth saving from a pane toggle. */
    private Saved last;
    /** The newest session seen, busy or not: what {@link #close} saves. */
    private volatile Doc latest;

    /** What decides whether a session needs saving again; everything in a {@link Conversation} but the times. */
    private record Saved(Doc.Meta meta, Transcript transcript, MapGraph graph, Map<String, Layout.Point> positions,
                         Doc.AgentInfo agent) {

        static Saved of(Doc d) {
            return new Saved(d.meta(), d.transcript(), d.mind().graph(), d.mind().positions(), d.agent());
        }
    }

    Archive(Path dir, Executor offload) {
        this.dir = dir;
        this.offload = offload;
    }

    /** The folder the files go in. */
    Path dir() {
        return dir;
    }

    /** Start saving. */
    void arm() {
        armed = true;
    }

    /** The model's listener: hand the session to the offload lane if a turn has ended with something new in it. */
    void changed(Doc d) {
        if (!armed) {
            return;
        }
        latest = d;
        if (d.busy()) {
            return;
        }
        Handed h = handOver(d);
        if (h != null) {
            offload.execute(() -> write(h));
        }
    }

    /**
     * The application is closing: save the session as it is now, on this thread, even mid-turn, since there is no
     * later. What was streaming loads as finished, and an ask nobody answered as lapsed.
     */
    @Override
    public void close() {
        Doc d = latest;
        if (armed && d != null) {
            Handed h = handOver(d);
            if (h != null) {
                write(h);
            }
        }
    }

    /** A snapshot on its way to disk, and its number. */
    private record Handed(Conversation conversation, long number) {
    }

    /** Number {@code d} for writing and return it as kept, or null if it is empty or nothing changed. */
    private synchronized Handed handOver(Doc d) {
        if (d.transcript().size() == 0) {
            return null;
        }
        Saved now = Saved.of(d);
        if (Objects.equals(now, last)) {
            return null;
        }
        last = now;
        long number = handed.incrementAndGet();
        newest.merge(d.meta().id(), number, Math::max);
        return new Handed(conversation(d), number);
    }

    /** The session as it is kept. */
    static Conversation conversation(Doc d) {
        Doc.AgentInfo a = d.agent();
        return new Conversation(d.meta().id(), d.meta().title(), d.meta().started(), System.currentTimeMillis(),
                new Conversation.Agent(a.sessionId(), a.model(), a.cwd(), a.costUsd(), a.turns(), a.inputTokens(),
                        a.outputTokens()),
                d.transcript(), d.mind().graph(), d.mind().positions(), d.mind().turn());
    }

    /**
     * Every saved conversation, newest first, read on the offload lane and handed to {@code done} there. A file that
     * does not read is left out, and logged.
     */
    void list(Consumer<List<Summary>> done) {
        offload.execute(() -> done.accept(summaries()));
    }

    /** {@link #list}'s work, on the calling thread. Disk I/O: never on the frame loop or a listener. */
    List<Summary> summaries() {
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

    /**
     * Read conversation {@code id} on the offload lane, and hand it to {@code done} there, or say to {@code failed}
     * why it could not be.
     */
    void load(String id, Consumer<Conversation> done, Consumer<String> failed) {
        offload.execute(() -> {
            Path f = file(id);
            try {
                done.accept(ConversationCodec.decode(Files.readString(f, StandardCharsets.UTF_8)));
            } catch (IOException | IllegalArgumentException e) {
                LOG.warn("could not read conversation {}", f, e);
                failed.accept("Could not open that conversation: " + e.getMessage());
            }
        });
    }

    /** The file conversation {@code id} is kept in. */
    Path file(String id) {
        return dir.resolve(id + ".json");
    }

    private void write(Handed h) {
        Conversation c = h.conversation();
        String id = c.id();
        synchronized (writing) {
            if (newest.getOrDefault(id, 0L) != h.number()) {
                return;
            }
            Path target = file(id);
            Path temp = dir.resolve(id + ".json.tmp");
            try {
                Files.createDirectories(dir);
                Files.writeString(temp, ConversationCodec.encode(c), StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                LOG.debug("saved conversation {} to {}", id, target);
            } catch (IOException e) {
                LOG.warn("could not save conversation {} to {}", id, target, e);
            }
        }
    }
}
