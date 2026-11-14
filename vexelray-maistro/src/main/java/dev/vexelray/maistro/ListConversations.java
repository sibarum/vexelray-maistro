package dev.vexelray.maistro;

import dev.vexelray.framework.api.Job;

/** Reads the saved conversations' summaries, on the job lane. A file that does not read is left out, and logged. */
@Job
public final class ListConversations {

    public Work.Listed run(Work.ListAll l) {
        return new Work.Listed(Archive.summaries(l.dir()));
    }
}
