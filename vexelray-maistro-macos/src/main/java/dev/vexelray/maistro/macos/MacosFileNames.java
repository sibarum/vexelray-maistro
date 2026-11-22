package dev.vexelray.maistro.macos;

import dev.vexelray.maistro.spi.FileNames;

/**
 * This system's reading of a path: only a forward slash separates, and any name but an empty one is the file it names,
 * a back slash in it included. Nothing is refused here that the system itself would open.
 */
public final class MacosFileNames implements FileNames {

    @Override
    public String separators(String given) {
        return given;
    }

    @Override
    public String problem(String path) {
        return "";
    }
}
