package dev.vexelray.maistro;

import dev.vexelray.framework.api.Job;

/** Writes a conversation to its file, on the job lane. {@link Archive#write} is the work, and its rules. */
@Job
public final class WriteConversation {

    public Work.Written run(Work.Write w) {
        return Archive.write(w);
    }
}
