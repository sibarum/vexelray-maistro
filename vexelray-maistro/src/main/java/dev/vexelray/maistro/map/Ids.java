package dev.vexelray.maistro.map;

/** The one rule for node ids, shared by every record that holds one. */
final class Ids {

    private Ids() {
    }

    /** {@code id} if it is a usable node id: non-blank, no whitespace, no double quote. */
    static String check(String id) {
        if (!valid(id)) {
            throw new IllegalArgumentException("not a node id: " + (id == null ? "null" : "\"" + id + "\""));
        }
        return id;
    }

    static boolean valid(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || c == '"') {
                return false;
            }
        }
        return true;
    }
}
