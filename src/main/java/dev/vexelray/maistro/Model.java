package dev.vexelray.maistro;

import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Doc}, and the only way to change it.
 *
 * <h2>Relative edits, not absolute writes</h2>
 *
 * <p>Every change here is a <em>function applied to whatever the current value turns out to be</em>, committed
 * through {@link State}. A streamed delta from the agent and a message the human sends both land, in some
 * order, and neither is lost. The alternative — read the value, compute a whole new one, write it back — produces
 * something perfectly coherent with one change missing whenever two handlers overlap, and nothing reports it.
 *
 * <p>One generic committer rather than one per command. The names would be the vocabulary a replicated peer binds
 * to, and nothing replicates this; splitting it is a mechanical change the compiler drives when something does.
 *
 * <h2>Handlers run on workers; this is the serialisation point</h2>
 *
 * <p>Nothing else in this application needs a lock for the session's shape, and if something starts to, that is
 * the signal that state has escaped this class.
 */
final class Model {

    private final State<Doc> state;
    private final Committer<Doc, UnaryOperator<Doc>> edit;

    Model() {
        State.Builder<Doc> builder = State.of(Doc.initial());
        // Declared before build, held as a handle: State refuses a committer it was not built with, which is
        // what stops an unrelated component minting its own way to write this.
        this.edit = builder.mutation("doc.edit", (current, change) -> change.apply(current));
        this.state = builder.build();
    }

    /** The latest coherent snapshot. Lock-free, safe from any thread. */
    Doc doc() {
        return state.value();
    }

    /** Apply a change to whatever the session currently is. */
    void change(UnaryOperator<Doc> change) {
        state.commit(edit, change);
    }

    /** Toggle one pane. */
    void toggle(Doc.Pane pane) {
        change(d -> d.withPanes(d.panes().toggle(pane)));
    }

    /** The human picked a node of the mind map: focus it, and start the next message from it. */
    void pick(String nodeId) {
        change(d -> d.withMind(m -> m.picked(nodeId)));
    }

    /** The next message starts from nowhere in particular. */
    void unanchor() {
        change(d -> d.withMind(Doc.Mind::unanchored));
    }

    /** Say something on the status line. */
    void say(String message) {
        change(d -> d.withStatus(message));
    }

    /**
     * React to every change, in order, one at a time, always finishing on the newest.
     *
     * <p>{@code State.onCommit} delivers on the committing thread <em>after</em> its compare-and-set, and handlers
     * commit from a pool — so two finishing together reach a listener as version 6 then 5, and a listener that
     * redraws from the document would end on a stale one. {@code onCommitLatest} serialises delivery and drops a
     * snapshot older than the last delivered.
     *
     * <p><b>It serialises by making a committer wait</b> for any delivery already running on another thread. So
     * never commit here while holding a lock the listener also takes: that is two threads each waiting for the
     * other, with nothing thrown.
     *
     * <p>And the order it keeps is among its own deliveries only. A listener that is also called directly, from
     * somewhere else, can be overtaken by an older delivery; read {@link #doc()} inside it rather than trusting the
     * snapshot it was handed, as {@link Session#remember} does.
     */
    void onChange(Consumer<Doc> listener) {
        state.onCommitLatest(v -> listener.accept(v.value()));
    }

    /** The version counter — what a test waits on to know a change landed. */
    long version() {
        return state.version();
    }
}
