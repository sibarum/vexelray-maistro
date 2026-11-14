package dev.vexelray.maistro;

import dev.vexelray.framework.api.Job;

/**
 * Asks Claude Code whether it is signed in ({@code claude auth status}), on the job lane: it is a process to wait on,
 * and the GUI thread must not wait. At startup, and on Check again.
 */
@Job
public final class CheckAccount {

    public Work.AccountChecked run(Work.AccountCheck c) {
        return new Work.AccountChecked(c.check(), Conductor.account(c.claude()));
    }
}
