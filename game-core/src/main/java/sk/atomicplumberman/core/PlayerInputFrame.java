package sk.atomicplumberman.core;

import java.util.Objects;

/**
 * Normalized input of one slot for one tick. {@code bomb} and {@code action} are press edges,
 * {@code bombHeld} is the button state (Grab carries while held and throws on release).
 * The device layer resolves the latest direction and ignores OS key repeat.
 */
public record PlayerInputFrame(Direction direction, boolean bomb, boolean bombHeld, boolean action) {
    public static final PlayerInputFrame NEUTRAL = new PlayerInputFrame(Direction.NONE, false, false, false);

    public PlayerInputFrame {
        Objects.requireNonNull(direction, "direction");
    }

    /** A bomb press that is released before the next tick, no action. */
    public PlayerInputFrame(Direction direction, boolean bomb) {
        this(direction, bomb, false, false);
    }
}
