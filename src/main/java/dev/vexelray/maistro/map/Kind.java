package dev.vexelray.maistro.map;

import java.util.Locale;
import java.util.Optional;

/** What a node on the mind map stands for. The view may draw each kind differently; the graph does not care. */
public enum Kind {
    /** An idea in the conversation. The default when a reply names no kind. */
    CONCEPT,
    /** A file in the workspace. */
    FILE,
    /** A VexelRay application the agent drives or talks about. */
    APP,
    /** An open question, or another reading of an ambiguous one, left as a place the human may go next. */
    QUESTION;

    /** The kind spelled {@code word} in the map protocol (any case), or empty if it is not one. */
    public static Optional<Kind> parse(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String w = word.toLowerCase(Locale.ROOT);
        for (Kind k : values()) {
            if (k.word().equals(w)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** How the map protocol spells this kind. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
