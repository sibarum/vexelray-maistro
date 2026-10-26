package dev.vexelray.maistro.tools;

import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.gui.automation.cli.AutomationClient;
import dev.vexelray.gui.automation.cli.Reply;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The VexelRay applications running on this machine with their automation socket on, and a way to ask one something.
 * The seam the tools are tested through; {@link #LOCAL} is the real one.
 */
public interface Apps {

    /**
     * One running application, from the file it writes ({@code ~/.vexelray/automation/<app>-<pid>.auto}).
     *
     * @param name    its application name, as {@code ottermate list} shows it
     * @param title   its window title, or {@code -}
     * @param pid     its process id
     * @param port    its automation socket
     * @param started when it started, ISO-8601
     * @param cwd     its working directory, or {@code -}
     */
    record App(String name, String title, long pid, int port, String started, String cwd) {
    }

    /** The running applications, oldest first. */
    List<App> running();

    /**
     * Sends {@code commands} to {@code app} over one connection, in order, and gives back every reply. Stops after
     * the first that answers {@code err}, as an {@code ottermate} script does: each command assumes the last worked.
     *
     * @throws IOException when the application cannot be reached, or stops answering
     */
    List<Reply> send(App app, List<String> commands) throws IOException;

    /** The machine's own: {@link Discovery#live()} and an {@link AutomationClient} per call. */
    Apps LOCAL = new Apps() {
        @Override
        public List<App> running() {
            List<App> out = new ArrayList<>();
            for (Discovery.Entry e : Discovery.live()) {
                out.add(new App(e.app(), e.title(), e.pid(), e.port(), e.started(), e.get("cwd")));
            }
            return out;
        }

        @Override
        public List<Reply> send(App app, List<String> commands) throws IOException {
            List<Reply> replies = new ArrayList<>();
            try (AutomationClient client = AutomationClient.attach(app.port())) {
                for (String c : commands) {
                    Reply r = client.send(c);
                    replies.add(r);
                    if (r.err()) {
                        break;
                    }
                }
            }
            return replies;
        }
    };
}
