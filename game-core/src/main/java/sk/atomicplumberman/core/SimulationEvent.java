package sk.atomicplumberman.core;

/**
 * Something that happened in the last tick, for audio, effects and diagnostics; never fed back into the rules.
 * {@code player} is the one who acted (kicker, puncher, thrower, picker) or was affected, -1 for none.
 */
public record SimulationEvent(Type type, int player, int x, int z) {
    public enum Type {
        BOMB_PLACED, EXPLOSION, BLOCK_DESTROYED, ITEM_PICKED, PLAYER_DIED, CELL_CLOSED,
        BOMB_KICKED, BOMB_PUNCHED, BOMB_THROWN, BOMB_LANDED, PLAYER_STUNNED, PLAYER_INFECTED, ITEM_DROPPED, PLAYER_WARPED,
        /** Diagnostic: a bomb found no landing place within the bounce limit and was removed. */
        BOMB_LOST
    }
}
