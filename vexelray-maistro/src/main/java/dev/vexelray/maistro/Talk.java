package dev.vexelray.maistro;

import dev.vexelray.framework.api.Job;

/**
 * Starts a strand's Claude Code and writes it the messages said to it, on the job lane: starting a process is I/O
 * (finding the CLI, writing the tools' config, launching it), and the GUI thread must not wait on it. The messages and
 * their order are the {@link Conductor}'s; the request names only the strand.
 */
@Job
public final class Talk {

    private final Conductor conductor;

    public Talk(Conductor conductor) {
        this.conductor = conductor;
    }

    public void run(Work.Deliver d) {
        conductor.deliver(d.strand());
    }
}
