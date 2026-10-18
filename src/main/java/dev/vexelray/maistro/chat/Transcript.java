package dev.vexelray.maistro.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The conversation, as one immutable value: every {@link Turn} in order, and the id the next one gets.
 *
 * <p>Changed only by functions of the current value, like everything else in {@code Doc}, so a streamed delta
 * arriving on the agent's reader thread and a message the human sends from a worker both land without either being
 * lost.
 */
public record Transcript(List<Turn> turns, long nextId) {

    public Transcript {
        turns = List.copyOf(turns);
    }

    public static Transcript empty() {
        return new Transcript(List.of(), 1);
    }

    /** Append a turn built from the next id. */
    public Transcript append(java.util.function.LongFunction<Turn> make) {
        List<Turn> next = new ArrayList<>(turns);
        next.add(make.apply(nextId));
        return new Transcript(next, nextId + 1);
    }

    /** Change the turn with {@code id}, whatever it currently is. A no-op on one that is not there. */
    public Transcript change(long id, UnaryOperator<Turn> change) {
        List<Turn> next = new ArrayList<>(turns.size());
        for (Turn t : turns) {
            next.add(t.id() == id ? change.apply(t) : t);
        }
        return new Transcript(next, nextId);
    }

    /** The last turn, or null when there is none. */
    public Turn last() {
        return turns.isEmpty() ? null : turns.getLast();
    }

    /** The agent's reply that is still being streamed, or null. */
    public Turn liveReply() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            if (t.who() == Turn.Who.AGENT && t.live()) {
                return t;
            }
        }
        return null;
    }

    /**
     * Stop everything that is still growing, as when the agent's turn ends or its process goes. A tool still waiting
     * on the human will now never be answered, so its ask lapses.
     */
    public Transcript settle() {
        List<Turn> next = new ArrayList<>(turns.size());
        for (Turn t : turns) {
            Turn s = t.live() ? t.finished(false) : t;
            next.add(s.asking() ? s.settled(Turn.Ask.LAPSED) : s);
        }
        return new Transcript(next, nextId);
    }

    /** The turns waiting on the human, oldest first. */
    public List<Turn> asking() {
        List<Turn> out = new ArrayList<>();
        for (Turn t : turns) {
            if (t.asking()) {
                out.add(t);
            }
        }
        return out;
    }

    public int size() {
        return turns.size();
    }
}
