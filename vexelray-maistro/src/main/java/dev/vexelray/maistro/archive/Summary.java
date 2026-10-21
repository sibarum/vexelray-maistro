package dev.vexelray.maistro.archive;

import dev.vexelray.maistro.chat.Turn;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/**
 * One saved conversation as a list shows it: enough to pick it, and nothing that needs the whole file.
 *
 * @param messages how many times the human spoke
 */
public record Summary(String id, String title, long saved, int messages) {

    /** Newest first: the order a list of conversations is shown in. */
    public static final Comparator<Summary> NEWEST_FIRST =
            Comparator.comparingLong(Summary::saved).reversed().thenComparing(Summary::id);

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    public Summary {
        title = title == null ? "" : title;
    }

    public static Summary of(Conversation c) {
        int messages = 0;
        for (Turn t : c.transcript().turns()) {
            if (t.who() == Turn.Who.HUMAN) {
                messages++;
            }
        }
        return new Summary(c.id(), c.title(), c.saved(), messages);
    }

    /** The title, or a stand-in for a conversation that never got one. */
    public String shownTitle() {
        return title.isBlank() ? "(untitled)" : title;
    }

    /** {@code 2026-10-18 14:20 · 3 messages}, in {@code zone}. */
    public String detail(ZoneId zone) {
        return WHEN.withZone(zone).format(Instant.ofEpochMilli(saved)) + " · " + messages
                + (messages == 1 ? " message" : " messages");
    }
}
