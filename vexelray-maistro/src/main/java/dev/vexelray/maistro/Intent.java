package dev.vexelray.maistro;

import sibarum.atchung.Topic;

/**
 * Something a chord asks of the window, heard by {@link Screen} on the GUI thread: what the chord was declared to send.
 *
 * <p>A chord is a declaration (a keymap action's {@code sends(sender, message)}): the dispatcher sends this when one
 * of the action's chords is pressed and runs nothing of the application's at that moment. Which chords those are is
 * the person's to change, from the title bar's key button.
 */
record Intent(Command command) {

    static final Topic<Intent> TOPIC = Topic.edge(Intent.class);

    /** Each command is a method on the screen, so there is no switch to keep in step with this list. */
    enum Command {
        TOGGLE_CONVERSATION {
            @Override
            void on(Screen s) {
                s.toggle(Doc.Pane.CONVERSATION);
            }
        },
        TOGGLE_MAP {
            @Override
            void on(Screen s) {
                s.toggle(Doc.Pane.MAP);
            }
        },
        TOGGLE_CONTEXT {
            @Override
            void on(Screen s) {
                s.toggle(Doc.Pane.CONTEXT);
            }
        },
        FOCUS_COMPOSER {
            @Override
            void on(Screen s) {
                s.focusComposer();
            }
        },
        ESCAPE {
            @Override
            void on(Screen s) {
                s.actions().escape();
            }
        },
        NEW_CONVERSATION {
            @Override
            void on(Screen s) {
                s.actions().newConversation();
            }
        },
        CONVERSATIONS {
            @Override
            void on(Screen s) {
                s.actions().conversations();
            }
        },
        BRANCH {
            @Override
            void on(Screen s) {
                s.actions().branch();
            }
        },
        ZOOM_IN {
            @Override
            void on(Screen s) {
                s.zoom(1);
            }
        },
        ZOOM_OUT {
            @Override
            void on(Screen s) {
                s.zoom(-1);
            }
        },
        ZOOM_RESET {
            @Override
            void on(Screen s) {
                s.zoom(0);
            }
        };

        abstract void on(Screen s);
    }
}
