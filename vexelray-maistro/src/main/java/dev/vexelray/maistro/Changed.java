package dev.vexelray.maistro;

import sibarum.atchung.Topic;

/**
 * The model has moved to {@code version}: the window is brought up to it in the next frame.
 *
 * <p>The model is committed from anywhere (the agent's reader thread, the offload lane, a press on the GUI thread), and
 * since vexelray-gui 0.2 a widget is built and written on the GUI thread only. So a commit says only that it happened,
 * and {@link Screen} redraws from {@link Model#doc()} when it hears this. A state topic with one key: a burst of
 * commits between two frames is one redraw, never a backlog, and the redraw reads the newest value anyway.
 */
record Changed(long version) {

    static final Topic<Changed> TOPIC = Topic.state(Changed.class, c -> "doc");
}
