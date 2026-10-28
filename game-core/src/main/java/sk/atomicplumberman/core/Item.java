package sk.atomicplumberman.core;

/** Pickups. KICK..GOLDFLAME are abilities a player keeps for the round; the rest act once. */
public enum Item {
    BOMB_UP, FLAME, SKATE, KICK, PUNCH, GRAB, SPOOGE, TRIGGER, JELLY, GOLDFLAME, DISEASE, SUPER_DISEASE, RANDOM;

    public boolean ability() { return ordinal() >= KICK.ordinal() && ordinal() <= GOLDFLAME.ordinal(); }

    public int bit() { return 1 << ordinal(); }

    /** Pairs from the original manual and guide: picking one up drops the other (plan 5.1). */
    boolean conflictsWith(Item other) {
        return switch (this) {
            case GRAB -> other == SPOOGE;
            case SPOOGE -> other == GRAB;
            case PUNCH -> other == TRIGGER;
            case JELLY -> other == TRIGGER;
            case TRIGGER -> other == PUNCH || other == JELLY;
            default -> false;
        };
    }
}
