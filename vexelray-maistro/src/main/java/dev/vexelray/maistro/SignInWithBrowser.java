package dev.vexelray.maistro;

import java.nio.file.Path;

import dev.vexelray.framework.api.Closer;
import dev.vexelray.framework.api.Source;
import dev.vexelray.maistro.agent.AgentProcess;
import dev.vexelray.maistro.agent.AgentSession;
import dev.vexelray.maistro.agent.AuthLogin;
import dev.vexelray.maistro.agent.ClaudeCli;
import sibarum.atchung.Sender;

/**
 * Signs Claude Code in, in the browser: {@code claude auth login}, one run per {@link Work.SignIn}, on a thread of its
 * own, since it lasts as long as the person takes in the browser. A source rather than a job for that reason, and
 * because a source can be stopped: Cancel sends {@link Work.StopSignIn}, which interrupts the run and ends the CLI and
 * what it started. The page's address is sent as soon as the CLI prints it ({@link Work.SignInLink}), for a browser
 * that did not open, and the run's reply says how it ended ({@link Work.SignInEnded}). {@link AuthLogin} is the work.
 */
@Source(stop = Work.StopSignIn.class)
public final class SignInWithBrowser {

    private final Sender<Work.SignInLink> links;

    public SignInWithBrowser(Sender<Work.SignInLink> links) {
        this.links = links;
    }

    public Work.SignInEnded run(Work.SignIn request, Closer closer) {
        ClaudeCli.Result found = ClaudeCli.locate(request.claude());
        if (!found.isFound()) {
            return new Work.SignInEnded(request.attempt(), false, false, found.reason());
        }
        Path exe = found.location().orElseThrow().executable();
        AuthLogin.Ended e = AuthLogin.run(exe, request.method(),
                AgentSession.Options.of(exe).childEnvironment(System.getenv()), AgentProcess.SYSTEM,
                p -> closer.closeOnStop(p::destroyTree),
                url -> links.send(new Work.SignInLink(request.attempt(), url)));
        return new Work.SignInEnded(request.attempt(), e.stopped(), e.signedIn(), e.detail());
    }
}
