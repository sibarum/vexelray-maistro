package dev.vexelray.maistro.agent;

/** Text that is not valid JSON, with the offset where reading stopped. */
public final class JsonException extends IllegalArgumentException {

    private final int offset;

    public JsonException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** The character offset in the text where the problem was found. */
    public int offset() {
        return offset;
    }
}
