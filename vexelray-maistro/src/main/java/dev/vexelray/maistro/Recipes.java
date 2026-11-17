package dev.vexelray.maistro;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.maistro.tools.AppTools;
import dev.vexelray.maistro.tools.Apps;
import dev.vexelray.maistro.tools.McpServer;
import dev.vexelray.maistro.tools.Toolset;
import java.util.List;
import sibarum.atchung.Sender;


/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code MaistroAppWiring} is written by {@code vexelray-framework-processor} while this project compiles, and
 * lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the look, the model and
 * the session store exist before the {@code Gui} does; the window's contents wait for the {@code Gui}, its clock and
 * its title bar. There is no phase to declare and none to get wrong. Open the generated file after a build to see
 * the order.
 *
 * <p>Nothing here waits for the window itself: what happens once it exists (bringing back last time's panes) is
 * {@link Screen}, the view that hears the framework's {@code WindowOpened}. No part takes the {@code Shell}, which the
 * processor refuses.
 *
 * <p>The automation sockets are not here. They come from {@code AutomationStarter}, which {@code MaistroApp}
 * names in its {@code @VexelApp} (see {@link Maistro}).
 *
 * <h2>What is not here is the point</h2>
 *
 * <p>Opening an input backend, installing a clipboard, remembering where the window was, attaching a clock, wiring
 * the frame loop with its wakes and its pacing, parsing the command line, installing the dialogs, and closing
 * everything in the right order are all {@code vexelray-framework}'s. So is closing the parts that are
 * {@code AutoCloseable}.
 *
 * <p>Adding a part is a method here. It returns an interface when other code should be able to swap it; this
 * package's own classes may be returned as they are, because nothing outside the package could hold a call site
 * against them anyway.
 */
@Configuration
final class Recipes {

    /**
     * The look, and the smallest window this UI is still coherent in. Built from nothing the framework makes, so it
     * exists in the first phase and the framework applies it the moment it creates the {@code Gui}: "the theme is
     * applied before the first widget" is structural rather than a comment somebody has to keep obeying.
     */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(Maistro.MIN_W_EM), Length.rem(Maistro.MIN_H_EM));
    }

    /** The one authoritative state, built before the tree because every label is a view onto it. */
    @Provides
    Model model() {
        return new Model();
    }

    /** What is remembered between runs, over the framework's one settings store. */
    @Provides
    Session session(Settings settings, Model model, Sender<Work.SaveSettings> saves) {
        return new Session(settings, model, saves);
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window — so a test can build
     * the real tree headless ({@code VexelApplication.tree}), which {@code UiTest} does.
     *
     * <p>The clock is for motion: {@link Motion} holds the tempi every widget here animates with. A change to the
     * session is committed on whichever thread made it, and a widget may be written on the GUI thread only, so a
     * commit says {@link Changed} and {@link Screen} redraws in the next frame. What is remembered for next time is
     * noted on the same thread, and written to disk by a job.
     */
    @Provides
    Ui ui(Gui gui, Model model, TitleBar titleBar, Session session, Sender<Changed> changed) {
        Ui ui = new Ui(gui, new Motion(gui), model, titleBar);
        model.onChange(doc -> {
            changed.send(new Changed(model.version()));
            session.remember();
        });
        return ui;
    }

    /**
     * The agent: Claude Code, run as a child process in its streaming mode, and what turns its events into changes
     * to the session. Built with the model and nothing graphical, so it exists before the tree; it starts no process
     * until the first message is sent, so a tree built headless for a test never launches one. Starting one is the
     * {@link Talk} job's, reached through {@code deliveries}, so the GUI thread never waits on a process.
     *
     * <p>Each launch option is a setting, so {@code --claude=C:/path/claude.exe}, {@code --model=opus} or
     * {@code --permissionMode=acceptEdits} on the command line changes it for one run. An empty {@code claude}
     * looks for the CLI on {@code PATH} and then in the Claude desktop app's own copy.
     */
    @Provides
    Conductor conductor(Model model,
                        @Setting(value = "claude", def = "") String claude,
                        @Setting(value = "model", def = "") String agentModel,
                        @Setting(value = "permissionMode", def = "default") String permissionMode,
                        @Setting(value = "cwd", def = "") String cwd,
                        Sender<Work.Deliver> deliveries) {
        return new Conductor(model, new Conductor.Launch(claude, agentModel, permissionMode, cwd), toolset(),
                deliveries);
    }

    /**
     * The tools Maistro gives the agent, served from this process over the CLI's own pipes: the read-only ones that
     * look at the other running VexelRay applications ({@link AppTools}). They run without asking, since none of them
     * changes anything. Their config file and the pictures {@code shot} passes through live under the application's
     * home; nothing is written until a process starts. Owned, and closed, by the conductor.
     */
    private static Toolset toolset() {
        AppHome home = AppHome.of(Maistro.APP);
        AppTools apps = new AppTools(Apps.LOCAL, home.file("shots"));
        return new Toolset(new McpServer("maistro", Maistro.APP, apps.tools()), home.file("mcp.json"),
                List.of("apps", "tree", "find", "shot"));
    }

    /**
     * Every conversation, kept on disk under the application's home ({@code ~/.maistro/conversations}, or
     * under {@code -Dmaistro.home}), written by a job when a turn ends. Writes nothing until
     * {@link Screen} arms it, once the window is open.
     */
    @Provides
    Archive archive(Model model, Sender<Work.Write> writes, Sender<Work.ListAll> lists, Sender<Work.Read> reads) {
        Archive archive = new Archive(AppHome.of(Maistro.APP).file("conversations"), writes, lists, reads);
        model.onChange(archive::changed);
        return archive;
    }

    /** Every command. The chords for them, zoom's among them, are {@link Screen}'s. */
    @Provides
    Actions actions(Model model, Ui ui, Conductor conductor, Archive archive, Sender<Work.AccountCheck> checks) {
        return new Actions(model, ui, conductor, archive, checks);
    }
}
