package sk.atomicplumberman.content;

import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Gameplay checks of plan 6.4 beyond the format checks of {@link ArenaLayout}: no enclosed start,
 * opponents connected once crates are gone (team arenas: reachable by a throw), and an escape from
 * every start. Seeded fill only turns {@code ?} into crates, so treating every {@code ?} as a crate
 * is the worst case of all seeds.
 */
public final class ArenaValidator {
    private static final int[][] STEPS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private ArenaValidator() { }

    public static List<String> problems(ArenaLayout arena, RulesetDefinition base) {
        var problems = new ArrayList<String>();
        var rules = arena.rules(base);
        var starts = arena.starts();
        if (starts.size() < 2) problems.add("starts: at least two are needed");
        for (int i = 0; i < starts.size(); i++) {
            Cell s = starts.get(i);
            long free = java.util.Arrays.stream(STEPS).filter(d -> arena.cell(s.x() + d[0], s.z() + d[1]) == '.').count();
            // Counting neighbours is not enough (plan 6.4); the timed escape below decides, this only rejects enclosed starts.
            if (free < 1) problems.add("starts[" + i + "]: the start is enclosed");
            if (!canEscape(arena, s, rules)) problems.add("starts[" + i + "]: no escape from an own bomb before it explodes");
        }
        // Without crates, everyone must be able to walk to everyone else; sides of a team arena meet by throws.
        for (int i = 0; i < starts.size(); i++) {
            var reach = distances(arena, starts.get(i), false);
            for (int j = 0; j < starts.size(); j++) {
                boolean opponents = !arena.teams() || i % 2 != j % 2;
                Cell other = starts.get(j);
                if (!opponents || reach[other.z()][other.x()] >= 0) continue;
                if (!arena.teams()) problems.add("starts[" + i + "] and starts[" + j + "]: not connected");
                else if (!throwReaches(arena, reach, distances(arena, other, false), rules.throwCells())) {
                    problems.add("starts[" + i + "] and starts[" + j + "]: sides cannot reach each other by a throw");
                }
            }
        }
        return problems;
    }

    /**
     * Timed escape: some cell of the start area must allow a first bomb from which, walking at base speed,
     * a cell outside its cross is reached before the fuse ends, with one cell of margin. Crates and
     * seeded cells block walking and stop flames. Bombing next to the start and stepping behind a
     * pillar is the classic opening, so the bomb need not be on the start cell itself.
     */
    static boolean canEscape(ArenaLayout arena, Cell start, RulesetDefinition rules) {
        int ticksPerCell = (int) Math.ceil(Simulation.TICKS_PER_SECOND * 1000.0 / rules.speed());
        int reachable = rules.fuseTicks() / ticksPerCell - 1;
        var area = distances(arena, start, true);
        for (int bz = 0; bz < arena.height(); bz++) {
            for (int bx = 0; bx < arena.width(); bx++) {
                if (area[bz][bx] < 0) continue;
                var bomb = new Cell(bx, bz);
                var dist = distances(arena, bomb, true);
                for (int z = 0; z < arena.height(); z++) {
                    for (int x = 0; x < arena.width(); x++) {
                        if (dist[z][x] >= 0 && dist[z][x] <= reachable && !inBlast(arena, bomb, x, z, rules.startRange())) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean inBlast(ArenaLayout arena, Cell bomb, int x, int z, int range) {
        if (x == bomb.x() && z == bomb.z()) return true;
        if (x != bomb.x() && z != bomb.z()) return false;
        int dx = Integer.signum(x - bomb.x());
        int dz = Integer.signum(z - bomb.z());
        int steps = Math.abs(x - bomb.x()) + Math.abs(z - bomb.z());
        if (steps > range) return false;
        for (int i = 1; i < steps; i++) {
            if ("#B?".indexOf(arena.cell(bomb.x() + dx * i, bomb.z() + dz * i)) >= 0) return false;
        }
        return true;
    }

    /** Breadth-first walking distance; {@code cratesBlock} treats B and ? as walls. Teleports link their pair. */
    static int[][] distances(ArenaLayout arena, Cell from, boolean cratesBlock) {
        int[][] dist = new int[arena.height()][arena.width()];
        for (int[] row : dist) java.util.Arrays.fill(row, -1);
        var queue = new ArrayDeque<Cell>();
        dist[from.z()][from.x()] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            var next = new ArrayList<Cell>();
            for (int[] d : STEPS) next.add(new Cell(c.x() + d[0], c.z() + d[1]));
            char here = arena.cell(c.x(), c.z());
            if (here >= 'a' && here <= 'z' && here != 'v') next.add(partner(arena, c, here));
            for (Cell n : next) {
                char cell = arena.cell(n.x(), n.z());
                if (cell == '#' || cratesBlock && (cell == 'B' || cell == '?') || dist[n.z()][n.x()] >= 0) continue;
                dist[n.z()][n.x()] = dist[c.z()][c.x()] + 1;
                queue.add(n);
            }
        }
        return dist;
    }

    private static Cell partner(ArenaLayout arena, Cell self, char letter) {
        for (int z = 0; z < arena.height(); z++) {
            for (int x = 0; x < arena.width(); x++) {
                if (arena.cell(x, z) == letter && (x != self.x() || z != self.z())) return new Cell(x, z);
            }
        }
        throw new IllegalStateException("teleport pairs are validated by ArenaLayout");
    }

    /** A bomb thrown from a cell one side reaches lands, after wrapping, on a cell the other side reaches. */
    private static boolean throwReaches(ArenaLayout arena, int[][] from, int[][] to, int cells) {
        for (int z = 0; z < arena.height(); z++) {
            for (int x = 0; x < arena.width(); x++) {
                if (from[z][x] < 0) continue;
                for (int[] d : STEPS) {
                    int tx = x;
                    int tz = z;
                    for (int i = 0; i < cells; i++) {
                        tx = wrap(tx + d[0], arena.width());
                        tz = wrap(tz + d[1], arena.height());
                    }
                    if (to[tz][tx] >= 0) return true;
                }
            }
        }
        return false;
    }

    private static int wrap(int value, int size) {
        return value < 1 ? size - 2 : value > size - 2 ? 1 : value;
    }
}
