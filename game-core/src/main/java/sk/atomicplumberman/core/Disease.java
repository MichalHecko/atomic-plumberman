package sk.atomicplumberman.core;

/** Negative effects (plan 5.3). SLOW and FAST are opposites: the newer one replaces the other. */
public enum Disease {
    SLOW, FAST, REVERSED, NO_BOMBS, FORCED_BOMBS, SHORT_FUSE, SHORT_RANGE;

    Disease opposite() {
        return this == SLOW ? FAST : this == FAST ? SLOW : null;
    }
}
