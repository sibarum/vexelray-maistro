package dev.vexelray.maistro;

import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.framework.api.Job;

/**
 * Writes the framework's settings store to disk, on the job lane. What changed was put in memory by whoever sent this;
 * {@code save} writes the whole store, under its own lock, so two saves in either order leave the newest on disk.
 */
@Job
public final class SaveSettings {

    private final Settings settings;

    public SaveSettings(Settings settings) {
        this.settings = settings;
    }

    public void run(Work.SaveSettings s) {
        settings.save();
    }
}
