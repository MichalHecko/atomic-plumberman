package sk.atomicplumberman.core;

import java.util.List;

/** Screen-relative direction: UP decreases the row index regardless of world axes. */
public enum Direction {
    NONE(0, 0), UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    static final List<Direction> MOVES = List.of(UP, DOWN, LEFT, RIGHT);

    final int dx;
    final int dz;

    Direction(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() { return dx; }
    public int dz() { return dz; }

    boolean perpendicularTo(Direction other) {
        return this != NONE && other != NONE && (dx == 0) != (other.dx == 0);
    }
}
