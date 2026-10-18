package dev.vexelray.maistro.map;

/**
 * A node struck out of the map, kept so that it is not silently re-added by a later reply. Revocable with
 * {@link MapGraph#uncarve(String)}.
 *
 * @param id     the carved node's id
 * @param reason why, as stated by the human or the agent; never null
 * @param turn   the turn the carve was made in
 */
public record Carve(String id, String reason, int turn) {

    public Carve {
        id = Ids.check(id);
        reason = reason == null ? "" : reason;
    }
}
