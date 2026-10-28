package sk.atomicplumberman.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static sk.atomicplumberman.core.Simulation.SUB;

/** Rule scenarios from plan chapter 12.1; IDs refer to that table. */
class SimulationTest {
    static final List<String> PILLARS = List.of(
            "#######",
            "#.....#",
            "#.#.#.#",
            "#.....#",
            "#######");
    static final List<String> OPEN = List.of(
            "#########",
            "#.......#",
            "#.......#",
            "#.......#",
            "#.......#",
            "#.......#",
            "#########");
    static final List<String> CLASSIC = List.of(
            "#################",
            "#...B.B.B.B.B...#",
            "#.#.#.#.#.#.#.#.#",
            "#.B.B...B...B.B.#",
            "#.#.#.#.#.#.#.#.#",
            "#B..B.......B..B#",
            "#.#.#.#.#.#.#.#.#",
            "#B..B.......B..B#",
            "#.#.#.#.#.#.#.#.#",
            "#.B.B...B...B.B.#",
            "#.#.#.#.#.#.#.#.#",
            "#...B.B.B.B.B...#",
            "#################");
    static final List<Cell> CLASSIC_STARTS = List.of(new Cell(1, 1), new Cell(15, 1), new Cell(1, 11), new Cell(15, 11));

    static final PlayerInputFrame IDLE = PlayerInputFrame.NEUTRAL;
    static final PlayerInputFrame BOMB = new PlayerInputFrame(Direction.NONE, true);

    static PlayerInputFrame go(Direction d) { return new PlayerInputFrame(d, false); }

    static Simulation sim(List<String> rows, Cell... starts) {
        return new Simulation(new ArenaLayout("test", rows), List.of(starts), RulesetDefinition.CLASSIC, 1);
    }

    static void run(Simulation s, int ticks, PlayerInputFrame... frames) {
        for (int i = 0; i < ticks; i++) s.step(List.of(frames));
    }

    static void teleport(Simulation s, int slot, int x, int z) {
        s.players.get(slot).x = x * SUB + SUB / 2;
        s.players.get(slot).z = z * SUB + SUB / 2;
    }

    static Set<Cell> flameCells(Simulation s) {
        return s.flames.stream().map(f -> new Cell(f.x(), f.z())).collect(Collectors.toSet());
    }

    @Test void t01RayStopsBeforeSolidWall() {
        var s = sim(PILLARS, new Cell(2, 1), new Cell(5, 3));
        run(s, 1, BOMB, IDLE);
        run(s, 150, IDLE, IDLE);
        assertEquals(Set.of(new Cell(1, 1), new Cell(2, 1), new Cell(3, 1), new Cell(4, 1)), flameCells(s));
    }

    @Test void t02OnlyFirstDestructibleBlockBreaks() {
        var s = sim(List.of("#######", "#.BB..#", "#.#.#.#", "#.....#", "#######"), new Cell(1, 1), new Cell(5, 3));
        run(s, 1, BOMB, IDLE);
        run(s, 150, IDLE, IDLE);
        assertEquals('.', s.blocks[s.width + 2]);
        assertEquals('B', s.blocks[s.width + 3]);
        assertFalse(flameCells(s).contains(new Cell(3, 1)));
    }

    @Test void t03ChainedBombDetonatesInSameTickExactlyOnce() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 1));
        s.players.getFirst().capacity = 2;
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 3, 1);
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 7, 5);
        run(s, 149, IDLE, IDLE);
        assertEquals(151, s.tick());
        assertTrue(s.bombs.isEmpty());
        assertEquals(0, s.players.getFirst().active);
        assertEquals(Set.of(0, 1), s.flames.stream().map(Simulation.Flame::bomb).collect(Collectors.toSet()));
        assertTrue(s.flames.stream().allMatch(f -> f.start() == 150));
        run(s, 20, IDLE, IDLE);
        assertEquals(0, s.players.getFirst().active);
        assertFalse(s.isOver());
    }

    @Test void t04OverlappingFlamesBurnUntilTheLastOneEnds() {
        var s = sim(OPEN, new Cell(1, 3), new Cell(7, 1));
        s.players.getFirst().capacity = 3;
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 7, 5);
        run(s, 9, IDLE, IDLE);
        teleport(s, 0, 5, 3);
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 7, 5);
        run(s, 9, IDLE, IDLE);
        teleport(s, 0, 3, 1);
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 7, 5);
        var centre = new Cell(3, 3);
        run(s, 150 - (int) s.tick(), IDLE, IDLE);
        for (long t = 150; t < 197; t++) { // 197 = 20 + 150 + 27
            run(s, 1, IDLE, IDLE);
            assertTrue(flameCells(s).contains(centre), "burning at tick " + t);
            if (t == 170) assertEquals(3, s.flames.stream().filter(f -> new Cell(f.x(), f.z()).equals(centre)).count());
        }
        run(s, 1, IDLE, IDLE);
        assertFalse(flameCells(s).contains(centre));
    }

    @Test void t05SharedBlockResultIsIndependentOfBombOrder() {
        var rows = List.of("#########", "#..B....#", "#.......#", "#.......#", "#########");
        for (boolean swapped : new boolean[] {false, true}) {
            var left = new Cell(2, 1);
            var right = new Cell(4, 1);
            var s = swapped ? sim(rows, right, left) : sim(rows, left, right);
            int rightOwner = swapped ? 0 : 1;
            run(s, 1, BOMB, BOMB);
            run(s, 150, IDLE, IDLE);
            assertEquals('.', s.blocks[s.width + 3]);
            var rightFlames = s.flames.stream().filter(f -> f.owner() == rightOwner)
                    .map(f -> new Cell(f.x(), f.z())).collect(Collectors.toSet());
            assertEquals(Set.of(new Cell(3, 1), new Cell(4, 1), new Cell(5, 1), new Cell(6, 1),
                    new Cell(4, 2), new Cell(4, 3)), rightFlames);
            assertTrue(s.isOver());
            assertEquals(-1, s.winner());
        }
    }

    @Test void t06PlayerLeavesOwnBombButCannotReturn() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        var p = s.players.getFirst();
        run(s, 1, BOMB, IDLE);
        run(s, 5, go(Direction.RIGHT), IDLE);
        int out = p.x;
        run(s, 2, go(Direction.LEFT), IDLE);
        assertTrue(p.x < out, "still overlapping: stepping back is allowed");
        while (p.x - s.half < 2 * SUB) run(s, 1, go(Direction.RIGHT), IDLE);
        run(s, 30, go(Direction.LEFT), IDLE);
        assertEquals(2 * SUB, p.x - s.half);
        assertTrue(s.bombs.getFirst().passers == 0);
    }

    @Test void t07MaxSpeedNeitherTunnelsWallNorFire() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        var p = s.players.getFirst();
        p.speed = 5000;
        run(s, 30, go(Direction.UP), IDLE);
        assertEquals(SUB, p.z - s.half);
        var fire = new Simulation.Flame(4, 1, 0, 1000, 99, 1);
        s.flames.add(fire);
        for (int i = 0; i < 60 && p.alive; i++) {
            run(s, 1, go(Direction.RIGHT), IDLE);
            assertTrue(p.x + s.half - 1 < 5 * SUB);
        }
        assertFalse(p.alive);
        assertEquals(fire, p.killedBy);
        assertEquals(1, s.kills(1));
    }

    @Test void t08EveryoneDyingInOneTickIsADraw() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(2, 1));
        run(s, 150, BOMB, IDLE);
        assertFalse(s.isOver());
        run(s, 1, IDLE, IDLE);
        assertTrue(s.isOver());
        assertEquals(-1, s.winner());
        assertEquals(0, s.kills(0), "suicide -1, kill +1");
        assertThrows(IllegalStateException.class, () -> run(s, 1, IDLE, IDLE));
    }

    @Test void t10BombKeepsRangeFromPlacement() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        run(s, 1, BOMB, IDLE);
        s.players.getFirst().range = 5;
        run(s, 150, IDLE, IDLE);
        assertTrue(flameCells(s).contains(new Cell(3, 1)));
        assertFalse(flameCells(s).contains(new Cell(4, 1)));
    }

    @Test void t09HiddenItemAppearsWhenTheLastFlameEnds() {
        var s = sim(List.of("#######", "#.B...#", "#.#.#.#", "#.....#", "#######"), new Cell(1, 1), new Cell(5, 3));
        int cell = s.width + 2;
        s.hidden[cell] = Item.FLAME;
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 3, 3);
        run(s, 150, IDLE, IDLE); // detonation in tick 150
        assertEquals('.', s.blocks[cell]);
        run(s, 26, IDLE, IDLE); // through tick 176, flame lasts [150, 177)
        assertNull(s.item(2, 1));
        run(s, 1, IDLE, IDLE);
        assertEquals(Item.FLAME, s.item(2, 1));
        teleport(s, 0, 2, 1);
        run(s, 1, IDLE, IDLE);
        assertNull(s.item(2, 1));
        assertEquals(3, s.players.getFirst().range);
        assertTrue(s.events().stream().anyMatch(e -> e.type() == SimulationEvent.Type.ITEM_PICKED && e.player() == 0));
    }

    @Test void flameDestroysVisibleItemAndPickupsRespectLimits() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        s.items[s.width * 1 + 3] = Item.SKATE;
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 1, 4);
        run(s, 150, IDLE, IDLE);
        assertNull(s.item(3, 1), "flame burns the pickup and continues");
        assertTrue(flameCells(s).contains(new Cell(3, 1)));
        var p = s.players.getFirst();
        for (Item item : Item.values()) {
            for (int i = 0; i < 12; i++) {
                s.items[s.width * 4 + 1] = item;
                run(s, 1, IDLE, IDLE);
            }
        }
        assertEquals(8, p.capacity);
        assertEquals(8, p.range);
        assertEquals(RulesetDefinition.CLASSIC.maxSpeed(), p.speed);
    }

    @Test void seedDecidesRandomFillAndHiddenItems() {
        var rows = new ArrayList<>(CLASSIC);
        rows.replaceAll(row -> row.replace('B', '?'));
        var arena = new ArenaLayout("random", rows);
        var a = new Simulation(arena, CLASSIC_STARTS, RulesetDefinition.CLASSIC, 11);
        var b = new Simulation(arena, CLASSIC_STARTS, RulesetDefinition.CLASSIC, 11);
        var c = new Simulation(arena, CLASSIC_STARTS, RulesetDefinition.CLASSIC, 12);
        assertEquals(a.stateHash(), b.stateHash());
        assertNotEquals(String.valueOf(a.blocks), String.valueOf(c.blocks));
        for (var s : List.of(a, c)) {
            assertEquals(-1, String.valueOf(s.blocks).indexOf('?'));
            for (var start : CLASSIC_STARTS) assertEquals('.', s.block(start.x(), start.z()));
            long blocks = String.valueOf(s.blocks).chars().filter(ch -> ch == 'B').count();
            long items = java.util.Arrays.stream(s.hidden).filter(i -> i != null).count();
            assertTrue(blocks > 5 && items > 0 && items < blocks, blocks + " blocks, " + items + " items");
        }
    }

    /** Round of 2000 ticks, sudden death in the last 1800: cell k of the spiral closes at tick 230 + 30k. */
    static Simulation suddenDeath(Cell... starts) {
        var rules = RulesetDefinition.CLASSIC.with("roundTicks", 2000).with("suddenDeathTicks", 1800);
        return new Simulation(new ArenaLayout("test", OPEN), List.of(starts), rules, 1);
    }

    @Test void suddenDeathClosesFromTheBorderInwardWithWarning() {
        var s = suddenDeath(new Cell(4, 3), new Cell(6, 3));
        assertEquals(List.of(1 + 1 * 9, 2 + 1 * 9, 3 + 1 * 9), java.util.Arrays.stream(s.closing).limit(3).boxed().toList());
        assertEquals(new Cell(7, 2), new Cell(s.closing[7] % 9, s.closing[7] / 9), "top row, then down the right side");
        run(s, 200, IDLE, IDLE);
        assertEquals(List.of(new Cell(1, 1)), s.closingSoon());
        run(s, 31, IDLE, IDLE); // through tick 230
        assertEquals('#', s.block(1, 1));
        assertEquals(new Cell(2, 1), s.closingSoon().getFirst());
    }

    @Test void t19ClosingWinsOverExplosionInTheSameTick() {
        var s = suddenDeath(new Cell(4, 3), new Cell(6, 3));
        run(s, 140, IDLE, IDLE);
        teleport(s, 0, 5, 1);
        run(s, 1, BOMB, IDLE); // tick 140, explodes in tick 290 when (3, 1) closes
        teleport(s, 0, 4, 3);
        run(s, 150, IDLE, IDLE);
        assertEquals(291, s.tick());
        assertEquals('#', s.block(3, 1));
        assertTrue(flameCells(s).contains(new Cell(4, 1)));
        assertFalse(flameCells(s).contains(new Cell(3, 1)), "the new wall stops the ray");
    }

    @Test void closingRemovesBombAndEliminatesWithoutKiller() {
        var s = suddenDeath(new Cell(2, 1), new Cell(4, 3));
        run(s, 200, IDLE, IDLE);
        run(s, 1, BOMB, IDLE); // would explode in tick 350; (2, 1) closes in tick 260
        run(s, 59, IDLE, IDLE);
        assertEquals(1, s.bombs.size());
        run(s, 1, IDLE, IDLE);
        assertTrue(s.bombs.isEmpty());
        assertEquals(0, s.players.getFirst().active);
        assertFalse(s.players.getFirst().alive);
        assertNull(s.players.getFirst().killedBy);
        assertEquals(0, s.kills(0));
        assertEquals(0, s.kills(1));
        assertTrue(s.isOver());
        assertEquals(1, s.winner());
    }

    @Test void earlyTurnIsBufferedAndCornersAssist() {
        for (int pressAt : new int[] {3000, 2662}) {
            var s = sim(PILLARS, new Cell(1, 1), new Cell(5, 3));
            var p = s.players.getFirst();
            while (p.z < pressAt) run(s, 1, go(Direction.DOWN), IDLE);
            run(s, 40, go(Direction.RIGHT), IDLE);
            if (pressAt == 3000) assertTrue(p.x > 2 * SUB + SUB / 2, "turned into row 3");
            else assertEquals(2 * SUB - s.half, p.x, "too early: buffer expired, player stuck at the pillar");
        }
    }

    @Test void scriptedRoundsPlayAWholeMatch() {
        var noSuddenDeath = RulesetDefinition.CLASSIC.with("suddenDeathTicks", 0);
        var match = new Match(new ArenaLayout("test", PILLARS), List.of(new Cell(1, 1), new Cell(3, 1)), noSuddenDeath, 5);
        var seeds = new ArrayList<Long>();
        var draw = match.nextRound();
        seeds.add(draw.rng);
        while (!draw.isOver()) match.step(List.of(IDLE, IDLE));
        assertEquals(RulesetDefinition.CLASSIC.roundTicks(), draw.tick());
        assertEquals(-1, draw.winner());
        while (match.champion() < 0) {
            var round = match.nextRound();
            seeds.add(round.rng);
            match.step(List.of(BOMB, IDLE));
            for (int i = 0; i < 60; i++) match.step(List.of(go(Direction.DOWN), IDLE));
            for (int i = 0; i < 25; i++) match.step(List.of(go(Direction.RIGHT), IDLE));
            while (!round.isOver()) match.step(List.of(IDLE, IDLE));
            assertEquals(0, round.winner());
        }
        assertEquals(0, match.champion());
        assertEquals(4, match.rounds());
        assertEquals(3, match.wins(0));
        assertEquals(3, match.kills(0));
        assertEquals(4, Set.copyOf(seeds).size());
        assertThrows(IllegalStateException.class, match::nextRound);
    }

    /** Generative invariants of plan 12.1 on the classic layout with random inputs. */
    @Test void randomPlayKeepsBombAccountingAndCollisions() {
        for (int seed = 1; seed <= 30; seed++) {
            var replay = randomReplay(seed);
            var s = new Simulation(replay.arena(), replay.starts(), replay.rules(), replay.seed());
            s.players.forEach(p -> p.capacity = 3);
            for (var frames : replay.ticks()) {
                if (s.isOver()) break;
                s.step(frames);
                for (var p : s.players) {
                    assertEquals(s.bombs.stream().filter(b -> b.owner == p.id).count(), p.active);
                    assertTrue(p.active <= p.capacity);
                    if (!p.alive) continue;
                    for (int x = (p.x - s.half) / SUB; x <= (p.x + s.half - 1) / SUB; x++) {
                        for (int z = (p.z - s.half) / SUB; z <= (p.z + s.half - 1) / SUB; z++) {
                            var bomb = s.bombAt[z * s.width + x];
                            assertEquals('.', s.blocks[z * s.width + x], "player inside a block");
                            assertTrue(bomb == null || (bomb.passers & 1 << p.id) != 0, "player inside a bomb");
                        }
                    }
                }
                assertEquals(s.bombs.size(), java.util.Arrays.stream(s.bombAt).filter(b -> b != null).count());
            }
        }
    }

    @Test void replaySurvivesTextRoundTrip() {
        var replay = randomReplay(3);
        var decoded = Replay.decode(replay.encode());
        assertEquals(replay.encode(), decoded.encode());
        assertEquals(replay.run(), decoded.run());
        assertThrows(IllegalArgumentException.class, () -> Replay.decode(replay.encode().replace("\nt ", "\nt X")));
    }

    /** Random but reproducible play recorded until the round ends. */
    static Replay randomReplay(long seed) {
        var random = new Random(seed);
        var arena = new ArenaLayout("classic-test", CLASSIC);
        var s = new Simulation(arena, CLASSIC_STARTS, RulesetDefinition.CLASSIC, seed);
        var held = new ArrayList<>(Collections.nCopies(4, Direction.NONE));
        var ticks = new ArrayList<List<PlayerInputFrame>>();
        while (!s.isOver()) {
            var frames = new ArrayList<PlayerInputFrame>();
            for (int i = 0; i < 4; i++) {
                if (random.nextInt(20) == 0) held.set(i, Direction.values()[random.nextInt(5)]);
                frames.add(new PlayerInputFrame(held.get(i), random.nextInt(60) == 0));
            }
            ticks.add(frames);
            s.step(frames);
        }
        return new Replay(arena, CLASSIC_STARTS, List.of(0, 1, 2, 3), RulesetDefinition.CLASSIC, seed, ticks);
    }
}
