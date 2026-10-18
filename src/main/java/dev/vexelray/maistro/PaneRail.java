package dev.vexelray.maistro;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The icon rail: one toggle per pane, each on or off by itself.
 *
 * <p>Not the widget shelf's {@code Rail}, which shows one panel at a time and is the right thing for a tool
 * palette. The canvas's panes are views of one session side by side, and any of them may be up at once, so each
 * icon here is a toggle and the panes are laid out by {@link Ui}. A toggle draws on as the icon in the accent, so
 * which panes are up is seen at a glance.
 */
final class PaneRail {

    private final Node root;
    private final Map<Doc.Pane, IconButton> toggles = new EnumMap<>(Doc.Pane.class);

    PaneRail(Gui gui, Consumer<Doc.Pane> toggle) {
        root = gui.column()
                .width(Type.RAIL).height(Length.FILL)
                .padding(Type.GUTTER, Length.dp(0))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.CHROME))
                .border(Length.dp(1), Look.RULE);
        add(gui, toggle, Doc.Pane.CONVERSATION, Icon.TERMINAL, "Conversation (Ctrl+1)", Landmarks.RAIL_CONVERSATION);
        add(gui, toggle, Doc.Pane.MAP, Icon.WORKSPACE, "Mind map (Ctrl+2)", Landmarks.RAIL_MAP);
        add(gui, toggle, Doc.Pane.CONTEXT, Icon.SIDEBAR, "Context stream (Ctrl+3)", Landmarks.RAIL_CONTEXT);
        gui.landmark(Landmarks.RAIL, root);
    }

    private void add(Gui gui, Consumer<Doc.Pane> toggle, Doc.Pane pane, Icon icon, String name, String landmark) {
        IconButton button = new IconButton(gui, icon, name)
                .size(Type.TARGET, Type.ICON)
                .toggle(true)
                .tip(name)
                .onPress(() -> toggle.accept(pane));
        toggles.put(pane, button);
        gui.landmark(landmark, button.node());
        root.append(button.node());
    }

    Node node() {
        return root;
    }

    /** Draw each toggle as its pane is. */
    void show(Doc.Panes panes) {
        toggles.forEach((pane, button) -> button.on(panes.shows(pane)));
    }
}
