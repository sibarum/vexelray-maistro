package dev.vexelray.maistro.spi;

import java.util.List;
import java.util.ServiceLoader;

/**
 * How this operating system reads a path the agent wrote: which characters separate its parts, and which names it
 * would open as something other than what was named. One {@code vexelray-maistro-<os>} module provides it through
 * {@link ServiceLoader}, as {@link ClaudeInstall} is provided; the tools that resolve paths ({@code tools/files/Paths})
 * name no OS.
 */
public interface FileNames {

    /** {@code given} with every character this OS takes as a separator written as its own separator. */
    String separators(String given);

    /**
     * Why this OS would not open {@code path} (already through {@link #separators}) as the file it names, in words
     * that name the part of the path; empty when it would. A name the OS silently changes is such a name: it would act
     * on another file.
     */
    String problem(String path);

    /** The one set of rules on the class path. */
    static FileNames current() {
        List<FileNames> found = ServiceLoader.load(FileNames.class).stream().map(ServiceLoader.Provider::get)
                .toList();
        if (found.size() != 1) {
            throw new IllegalStateException((found.isEmpty() ? "no" : found.size() + "")
                    + " vexelray-maistro OS modules on the class path; the application's OS-activated profile adds one");
        }
        return found.getFirst();
    }
}
