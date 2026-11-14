package dev.vexelray.maistro;

import dev.vexelray.framework.api.Job;

/** Reads one saved conversation's file, on the job lane, or says why it could not. */
@Job
public final class ReadConversation {

    public Work.Loaded run(Work.Read r) {
        return Archive.read(r);
    }
}
