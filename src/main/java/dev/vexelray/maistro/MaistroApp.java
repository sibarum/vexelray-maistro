package dev.vexelray.maistro;

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The application declaration: {@link Maistro}'s facts, plus {@link AutomationStarter}, the automation sockets, in
 * every build, the release one included.
 *
 * <p>The sockets are off unless the launch asks ({@code --automation}, {@code VEXELRAY_AUTOMATION}) or a person
 * switches them on from the title bar's plug, and loopback-only when they are on, because remote control hands
 * whoever reaches it full control of the application's input. The plug's lamps show each one's port open or not and
 * anything connected now or earlier; {@code --automation.available=none} leaves them out of a run altogether. The
 * processor generates {@code MaistroAppWiring} from this.
 *
 * <p>{@code icon} is the mark the window wears: the same file {@code src/main/rc/vexelray-maistro.rc} links into the
 * executable, which the pom puts on the class path. Draw your own, render it to an {@code .ico}, and replace that
 * one file; see the README, <i>The icon</i>.
 */
@VexelApp(name = Maistro.APP, title = Maistro.TITLE, width = Maistro.W, height = Maistro.H,
        icon = Maistro.ICON, starters = AutomationStarter.class)
final class MaistroApp {

    private MaistroApp() {
    }
}
