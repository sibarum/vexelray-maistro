package dev.vexelray.maistro.windows;

import java.util.Locale;
import java.util.Set;

import dev.vexelray.maistro.spi.FileNames;

/**
 * Windows' reading of a path: a back slash and a forward slash both separate, and some names are not the file they
 * look like. Windows strips a trailing dot or space from a name, opens the device rather than a file for a reserved
 * name ({@code CON}, {@code NUL}, {@code COM1}, with any extension, and the superscript-digit forms), reads
 * {@code name:stream} as an alternate data stream, and allows none of {@code <>"|?*} or a control character in a name.
 * Each is refused, naming the part, rather than left to act on something the agent did not name. A verbatim or device
 * path ({@code \\?\}, {@code \\.\}) is refused too: it turns those rules off, which is not what a plain path means.
 */
public final class WindowsFileNames implements FileNames {

    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "COM¹", "COM²", "COM³",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT¹", "LPT²", "LPT³");

    private static final String FORBIDDEN = "<>\"|?*";

    @Override
    public String separators(String given) {
        return given.replace('/', '\\');
    }

    @Override
    public String problem(String p) {
        int start = 0;
        if (p.startsWith("\\\\?\\") || p.startsWith("\\\\.\\")) {
            return "a verbatim or device path (" + p.substring(0, 4) + ") is not taken; give the plain path";
        }
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') {
            start = 2;
        }
        for (String part : p.substring(start).split("\\\\", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                continue;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 32) {
                    return "'" + printable(part) + "' holds a control character, which Windows does not allow in a name";
                }
                if (c == ':') {
                    return "'" + part + "' holds ':', which Windows reads as a data stream of the file before it";
                }
                if (FORBIDDEN.indexOf(c) >= 0) {
                    return "'" + part + "' holds '" + c + "', which Windows does not allow in a name";
                }
            }
            char last = part.charAt(part.length() - 1);
            if (last == '.' || last == ' ') {
                return "'" + part + "' ends with " + (last == '.' ? "a dot" : "a space")
                        + ", which Windows strips, so it would name another file";
            }
            int dot = part.indexOf('.');
            String stem = (dot < 0 ? part : part.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
            if (RESERVED.contains(stem)) {
                return "'" + part + "' is a device name on Windows (" + stem + "), not a file";
            }
        }
        return "";
    }

    private static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
