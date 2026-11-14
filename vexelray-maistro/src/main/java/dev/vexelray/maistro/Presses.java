package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;

import java.util.function.Consumer;

/**
 * What a button's press or a field's Enter does, heard as the widget's own message.
 *
 * <p>Since vexelray-gui 0.2 a widget runs none of the application's code: a press is a {@link Button.Pressed} on the
 * bus, carrying the button's address, and whoever cares hears it in a mailbox routed to that address. These are that
 * mailbox, one per widget, delivered on the GUI thread in the frame the input arrived. Registering one is GUI-thread
 * work too, as building the widget is.
 *
 * <p>A widget that is taken out of the window keeps its route until the subscription is closed, so a pane that
 * rebuilds buttons closes the old ones' (it costs a map entry each, not a wrong press: a gone button sends nothing).
 */
final class Presses {

    private Presses() {
    }

    static Subscription on(Gui gui, Button button, Runnable then) {
        return gui.mailbox(Button.PRESSED, button.address(), pressed -> then.run());
    }

    static Subscription on(Gui gui, IconButton button, Runnable then) {
        return gui.mailbox(Button.PRESSED, button.address(), pressed -> then.run());
    }

    /** Enter in {@code field}: handed what the field said. */
    static Subscription submitted(Gui gui, TextField field, Consumer<String> then) {
        return gui.mailbox(TextField.SUBMITTED, field.address(), submitted -> then.accept(submitted.text()));
    }
}
