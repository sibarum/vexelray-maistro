package dev.vexelray.maistro.chat;

import java.util.List;

/**
 * One entry in the conversation: something the human said, something the agent said, a tool the agent ran, or a
 * note from Maistro itself (the agent started, the process ended, an error).
 *
 * <p>Immutable, and named by an {@code id} rather than its index, for the same reason {@code Doc} names its entries:
 * a streamed reply grows in place while other entries are appended after it, and an index read before an append is
 * somebody else's afterwards.
 *
 * @param id     stable for the life of the transcript
 * @param who    who is speaking
 * @param title  a short heading: the tool's name for a tool, empty otherwise
 * @param text   the body. Markdown for the agent, plain for the rest
 * @param live   still growing: a reply being streamed, or a tool that has not returned
 * @param failed the tool returned an error, or the turn ended in one
 * @param chips  what this turn touched, shown under it ({@code map · +3 nodes})
 * @param ask    for a tool: whether it waits on the human, and what they said
 * @param offers for a tool waiting on the human: the standing permissions it may be answered with, in words
 */
public record Turn(long id, Who who, String title, String text, boolean live, boolean failed, List<String> chips,
                   Ask ask, List<String> offers) {

    /** Who an entry is from. An enum with its label on it, so a view needs no switch to name one. */
    public enum Who {
        HUMAN("YOU"),
        AGENT("MAISTRO"),
        TOOL("TOOL"),
        NOTE("NOTE");

        private final String label;

        Who(String label) {
            this.label = label;
        }

        /** The uppercase label a view heads the entry with. */
        public String label() {
            return label;
        }
    }

    /**
     * Whether a tool waits on the human: not at all, still, or how it was settled. Each carries the chip a view shows
     * once it is settled, so a view needs no switch to name one.
     */
    public enum Ask {
        NONE(""),
        PENDING(""),
        ALLOWED("allowed"),
        /** Allowed, with a standing permission set up; the turn's chips say which, so this adds none. */
        ALWAYS(""),
        DENIED("denied"),
        /** Never answered: the turn was interrupted or the process ended while it waited. */
        LAPSED("not answered");

        private final String chip;

        Ask(String chip) {
            this.chip = chip;
        }

        /** What a settled ask says under its tool; empty for none, or one still waiting. */
        public String chip() {
            return chip;
        }
    }

    public Turn {
        ask = ask == null ? Ask.NONE : ask;
        offers = offers == null ? List.of() : List.copyOf(offers);
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        chips = List.copyOf(chips);
    }

    public static Turn human(long id, String text) {
        return new Turn(id, Who.HUMAN, "", text, false, false, List.of(), Ask.NONE, List.of());
    }

    public static Turn agent(long id, String text, boolean live) {
        return new Turn(id, Who.AGENT, "", text, live, false, List.of(), Ask.NONE, List.of());
    }

    public static Turn tool(long id, String name, String detail) {
        return new Turn(id, Who.TOOL, name, detail, true, false, List.of(), Ask.NONE, List.of());
    }

    public static Turn note(long id, String text, boolean failed) {
        return new Turn(id, Who.NOTE, "", text, false, failed, List.of(), Ask.NONE, List.of());
    }

    public Turn withText(String value) {
        return new Turn(id, who, title, value, live, failed, chips, ask, offers);
    }

    /** The turn has stopped growing; {@code error} marks a tool that returned one. */
    public Turn finished(boolean error) {
        return new Turn(id, who, title, text, false, failed || error, chips, ask, offers);
    }

    public Turn withChips(List<String> value) {
        return new Turn(id, who, title, text, live, failed, value, ask, offers);
    }

    public Turn withAsk(Ask value) {
        return new Turn(id, who, title, text, live, failed, chips, value, offers);
    }

    /** Settle the ask as {@code value}; what was on offer goes with it. */
    public Turn settled(Ask value) {
        return new Turn(id, who, title, text, live, failed, chips, value, List.of());
    }

    /** Wait on the human, offering {@code value} beside a plain Allow and Deny. */
    public Turn asking(List<String> value) {
        return new Turn(id, who, title, text, live, failed, chips, Ask.PENDING, value);
    }

    /** Waiting on the human's say-so. */
    public boolean asking() {
        return ask == Ask.PENDING;
    }

    /** The heading a view shows: the speaker, and for a tool its name. */
    public String heading() {
        return title.isEmpty() ? who.label() : who.label() + " · " + title;
    }
}
