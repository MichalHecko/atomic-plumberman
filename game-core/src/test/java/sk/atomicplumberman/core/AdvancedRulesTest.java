package sk.atomicplumberman.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static sk.atomicplumberman.core.Simulation.SUB;
import static sk.atomicplumberman.core.SimulationTest.*;

/** Abilities, flights, diseases and teams (plan 5, 4.5); IDs refer to the table in plan 12.1. */
class AdvancedRulesTest {
    static final PlayerInputFrame ACTION = new PlayerInputFrame(Direction.NONE, false, false, true);
    static final PlayerInputFrame HOLD = new PlayerInputFrame(Direction.NONE, false, true, false);
    static final PlayerInputFrame PRESS_HOLD = new PlayerInputFrame(Direction.NONE, true, true, false);
    static final List<String> WALLED = List.of(
            "#########",
            "#.......#",
            "#.......#",
            "#...#...#",
            "#.......#",
            "#.......#",
            "#########");

    static Simulation.Player p(Simulation s, int slot) { return s.players.get(slot); }

    static void give(Simulation s, int slot, Item... abilities) {
        for (Item item : abilities) p(s, slot).abilities |= item.bit();
    }

    /** Drops a bomb of {@code slot} at a cell and puts the player back where they were. */
    static Simulation.Bomb bombAt(Simulation s, int slot, int x, int z) {
        var player = p(s, slot);
        int px = player.x;
        int pz = player.z;
        teleport(s, slot, x, z);
        var frames = new java.util.ArrayList<PlayerInputFrame>(java.util.Collections.nCopies(s.players.size(), IDLE));
        frames.set(slot, BOMB);
        s.step(frames);
        player.x = px;
        player.z = pz;
        return s.bombs.getLast();
    }

    /** As a kick would: rolling at the kick speed. */
    static void roll(Simulation.Bomb b, Direction d) {
        b.state = Simulation.BombState.ROLLING;
        b.rolling = d;
        b.speed = RulesetDefinition.CLASSIC.rollSpeed();
    }

    static void idle(Simulation s, int ticks) {
        for (int i = 0; i < ticks; i++) s.step(java.util.Collections.nCopies(s.players.size(), IDLE));
    }

    static boolean happened(Simulation s, SimulationEvent.Type type) {
        return s.events().stream().anyMatch(e -> e.type() == type);
    }

    // ------------------------------------------------------------ Kick and Stop

    @Test void kickRollsTheBombUntilTheWall() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        give(s, 0, Item.KICK);
        var b = bombAt(s, 0, 3, 1);
        boolean kicked = false;
        for (int i = 0; i < 40 && !kicked; i++) {
            run(s, 1, go(Direction.RIGHT), IDLE);
            kicked = happened(s, SimulationEvent.Type.BOMB_KICKED);
        }
        assertTrue(kicked);
        idle(s, 60);
        assertEquals(Simulation.BombState.PLACED, b.state);
        assertEquals(7, b.x(), "stops centred in the last free cell before the wall");
        assertSame(b, s.bombAt[s.width + 7]);
        assertNull(s.bombAt[s.width + 3]);
    }

    @Test void kickIsBlockedByTheWallBehindAndNeedsTheAbility() {
        var s = sim(OPEN, new Cell(5, 1), new Cell(1, 5));
        var b = bombAt(s, 0, 7, 1);
        run(s, 40, go(Direction.RIGHT), IDLE);
        assertEquals(Simulation.BombState.PLACED, b.state, "no ability, no kick");
        give(s, 0, Item.KICK);
        run(s, 10, go(Direction.RIGHT), IDLE);
        assertEquals(Simulation.BombState.PLACED, b.state, "wall right behind the bomb");
        assertEquals(7, b.x());
    }

    @Test void t12HeadOnBombsStopWithoutPassingAndRollingStopsAtPlayers() {
        for (boolean swapped : new boolean[] {false, true}) {
            var s = sim(OPEN, new Cell(1, 1), new Cell(7, 1), new Cell(4, 5));
            var a = bombAt(s, swapped ? 1 : 0, 2, 3);
            var b = bombAt(s, swapped ? 0 : 1, 6, 3);
            roll(a, Direction.RIGHT);
            roll(b, Direction.LEFT);
            idle(s, 40);
            assertEquals(List.of(3, 5), List.of(a.x(), b.x()), "both want cell 4 at once, both stop");
            assertEquals(Simulation.BombState.PLACED, a.state);
            assertEquals(Simulation.BombState.PLACED, b.state);
        }
        var s = sim(OPEN, new Cell(1, 1), new Cell(5, 3));
        var b = bombAt(s, 0, 2, 3);
        roll(b, Direction.RIGHT);
        idle(s, 40);
        assertEquals(4, b.x(), "stops before the player; the impact is harmless");
        assertTrue(p(s, 1).alive);
    }

    @Test void stopHaltsTheLatestKickedBombAtTheNextCell() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        give(s, 0, Item.KICK);
        var b = bombAt(s, 0, 3, 1);
        while (b.state != Simulation.BombState.ROLLING) run(s, 1, go(Direction.RIGHT), IDLE);
        run(s, 1, ACTION, IDLE);
        idle(s, 30);
        assertEquals(Simulation.BombState.PLACED, b.state);
        assertTrue(b.x() <= 5, "stopped long before the wall at 7, at x " + b.x());
        assertEquals(SUB / 2, b.px % SUB, "centred");
    }

    @Test void rollingIntoFireDetonatesAtTheCellOfItsCentre() {
        var s = sim(OPEN, new Cell(1, 5), new Cell(7, 5));
        var b = bombAt(s, 0, 3, 1);
        s.flames.add(new Simulation.Flame(5, 1, 0, 1000, 99, 1));
        roll(b, Direction.RIGHT);
        for (int i = 0; i < 30 && s.bombs.contains(b); i++) idle(s, 1);
        assertFalse(s.bombs.contains(b));
        var explosion = s.events().stream().filter(e -> e.type() == SimulationEvent.Type.EXPLOSION).findFirst().orElseThrow();
        assertEquals(4, explosion.x(), "entering cell 5, its centre is still in cell 4");
    }

    @Test void rollingBombDoesNotCutItsOwnRays() {
        for (int t = 0; t < 16; t++) {
            var s = sim(OPEN, new Cell(1, 5), new Cell(7, 5));
            var b = bombAt(s, 0, 3, 2);
            roll(b, Direction.RIGHT);
            idle(s, t);
            b.explodesAt = s.tick();
            idle(s, 1);
            int at = s.events().stream().filter(e -> e.type() == SimulationEvent.Type.EXPLOSION).findFirst().orElseThrow().x();
            var row = s.flames.stream().filter(f -> f.z() == 2).map(Simulation.Flame::x).sorted().toList();
            var expected = java.util.stream.IntStream.rangeClosed(Math.max(1, at - 2), Math.min(7, at + 2)).boxed().toList();
            assertEquals(expected, row, "full range both ways from cell " + at + " after " + t + " ticks");
        }
    }

    // ------------------------------------------------------------ Punch and landings

    @Test void t13PunchFliesOverAWallAndLandsAfterItsFlightTime() {
        var s = sim(WALLED, new Cell(2, 3), new Cell(7, 1));
        give(s, 0, Item.PUNCH);
        var b = bombAt(s, 1, 3, 3);
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, ACTION, IDLE);
        assertEquals(Simulation.BombState.AIRBORNE, b.state);
        assertNull(s.bombAt[3 * s.width + 3], "a flying bomb does not block");
        idle(s, 11);
        assertEquals(Simulation.BombState.AIRBORNE, b.state);
        idle(s, 1);
        assertEquals(Simulation.BombState.PLACED, b.state);
        assertEquals(List.of(6, 3), List.of(b.x(), b.z()));
    }

    @Test void punchNeedsTheAbilityAndABombAhead() {
        var s = sim(WALLED, new Cell(2, 3), new Cell(7, 1));
        var b = bombAt(s, 1, 3, 3);
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, ACTION, IDLE);
        assertEquals(Simulation.BombState.PLACED, b.state);
        give(s, 0, Item.PUNCH);
        p(s, 0).facing = Direction.UP;
        run(s, 1, ACTION, IDLE);
        assertEquals(Simulation.BombState.PLACED, b.state, "nothing above the player");
    }

    @Test void t13LandingBouncesOffBlocksAndPlayersAndWrapsAtTheEdge() {
        var rows = List.of("#########", "#.......#", "#.......#", "#.....BB#", "#.......#", "#.......#", "#########");
        var s = sim(rows, new Cell(2, 3), new Cell(3, 1), new Cell(1, 5));
        give(s, 0, Item.PUNCH);
        var b = bombAt(s, 1, 3, 3);
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, ACTION, IDLE, IDLE);
        idle(s, 12 + 4 + 4); // lands on B at 6, bounces to B at 7, wraps to 1
        assertEquals(Simulation.BombState.PLACED, b.state);
        assertEquals(List.of(1, 3), List.of(b.x(), b.z()));

        var t = sim(OPEN, new Cell(2, 3), new Cell(6, 3), new Cell(1, 5));
        give(t, 0, Item.PUNCH);
        p(t, 1).capacity = 2;
        var c = bombAt(t, 2, 3, 3);
        p(t, 0).facing = Direction.RIGHT;
        run(t, 1, ACTION, IDLE, IDLE);
        idle(t, 12);
        assertTrue(happened(t, SimulationEvent.Type.PLAYER_STUNNED));
        assertEquals(1, p(t, 1).capacity, "the stunned player loses a bonus");
        assertEquals(Item.BOMB_UP, t.item(6, 2), "dropped on the first free cell above");
        assertEquals(Simulation.BombState.AIRBORNE, c.state, "bounces on");
        run(t, 10, IDLE, go(Direction.LEFT), IDLE);
        assertEquals(6 * SUB + SUB / 2, p(t, 1).x, "stunned for 18 ticks");
        idle(t, 4);
        assertEquals(List.of(7, 3), List.of(c.x(), c.z()));
    }

    @Test void t14FuseRunningOutInTheAirDetonatesOnLanding() {
        var s = sim(WALLED, new Cell(2, 3), new Cell(7, 1), new Cell(1, 5));
        give(s, 0, Item.PUNCH);
        var b = bombAt(s, 1, 3, 3);
        b.explodesAt = s.tick + 3;
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, ACTION, IDLE, IDLE);
        idle(s, 11);
        assertTrue(s.bombs.contains(b), "no explosion in mid-air");
        assertTrue(s.flames.isEmpty());
        idle(s, 1);
        assertFalse(s.bombs.contains(b));
        assertTrue(flameCells(s).contains(new Cell(6, 3)), "explodes where it landed");
    }

    @Test void landingInFireDetonatesAtOnce() {
        var s = sim(WALLED, new Cell(2, 3), new Cell(7, 1), new Cell(1, 5));
        give(s, 0, Item.PUNCH);
        var b = bombAt(s, 1, 3, 3);
        s.flames.add(new Simulation.Flame(6, 3, 0, 1000, 99, 2));
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, ACTION, IDLE, IDLE);
        idle(s, 12);
        assertFalse(s.bombs.contains(b));
    }

    // ------------------------------------------------------------ Grab

    @Test void grabPausesTheFuseWhileCarriedAndThrowsOnRelease() {
        var s = sim(WALLED, new Cell(2, 3), new Cell(7, 1));
        give(s, 0, Item.GRAB);
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, BOMB, IDLE);
        var b = s.bombs.getFirst();
        run(s, 1, PRESS_HOLD, IDLE);
        assertEquals(Simulation.BombState.CARRIED, b.state);
        assertEquals(1, p(s, 0).active, "a carried bomb still counts against capacity");
        run(s, 300, HOLD, IDLE);
        assertTrue(s.bombs.contains(b), "the held bomb does not tick");
        long release = s.tick;
        run(s, 1, IDLE, IDLE);
        assertEquals(Simulation.BombState.AIRBORNE, b.state);
        assertEquals(release + 149, b.explodesAt, "the remaining fuse resumes");
        idle(s, 12);
        assertEquals(List.of(5, 3), List.of(b.x(), b.z()), "three cells from the thrower, over the wall");
    }

    @Test void grabOnlyLiftsOwnBombsAndADeadCarrierDropsIt() {
        var s = sim(OPEN, new Cell(2, 3), new Cell(6, 1), new Cell(1, 5));
        give(s, 0, Item.GRAB);
        var foreign = bombAt(s, 1, 2, 3);
        run(s, 1, PRESS_HOLD, IDLE, IDLE);
        assertEquals(Simulation.BombState.PLACED, foreign.state);
        assertFalse(p(s, 0).carrying());
        teleport(s, 0, 4, 3);
        run(s, 1, BOMB, IDLE, IDLE);
        var own = s.bombs.getLast();
        run(s, 1, PRESS_HOLD, IDLE, IDLE);
        assertTrue(p(s, 0).carrying());
        s.flames.add(new Simulation.Flame(4, 3, s.tick, s.tick + 5, 99, 1));
        run(s, 1, HOLD, IDLE, IDLE);
        assertFalse(p(s, 0).alive);
        assertEquals(Simulation.BombState.PLACED, own.state);
        assertEquals(List.of(4, 3), List.of(own.x(), own.z()));
    }

    // ------------------------------------------------------------ Trigger, Spooge, Goldflame, Jelly

    @Test void triggerBombsWaitForActionOldestFirst() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        give(s, 0, Item.TRIGGER);
        p(s, 0).capacity = 2;
        var first = bombAt(s, 0, 3, 1);
        var second = bombAt(s, 0, 5, 3);
        idle(s, 400);
        assertTrue(s.bombs.contains(first) && s.bombs.contains(second));
        run(s, 1, ACTION, IDLE);
        assertFalse(s.bombs.contains(first));
        assertTrue(s.bombs.contains(second));
    }

    @Test void t11TriggerBombOfADeadOwnerGetsAFiniteFuse() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5), new Cell(7, 1));
        give(s, 0, Item.TRIGGER);
        var b = bombAt(s, 0, 4, 3);
        s.flames.add(new Simulation.Flame(1, 1, s.tick, s.tick + 5, 99, 1));
        idle(s, 1);
        assertFalse(p(s, 0).alive);
        assertFalse(b.trigger);
        assertTrue(b.explodesAt <= s.tick + 90);
        idle(s, 90);
        assertFalse(s.bombs.contains(b), "does not block the arena forever");
    }

    @Test void t15SpoogeLaysTheRemainingBombsUpToTheFirstObstacle() {
        var rows = List.of("#########", "#....B..#", "#.......#", "#.......#", "#.......#", "#.......#", "#########");
        var s = sim(rows, new Cell(1, 1), new Cell(7, 5));
        give(s, 0, Item.SPOOGE);
        p(s, 0).capacity = 6;
        p(s, 0).facing = Direction.RIGHT;
        run(s, 1, BOMB, IDLE);
        run(s, 2, IDLE, IDLE);
        run(s, 1, BOMB, IDLE);
        assertEquals(4, s.bombs.size(), "cells 1..4, the block at 5 stops the row");
        assertEquals(List.of(1, 2, 3, 4), s.bombs.stream().map(Simulation.Bomb::x).toList());

        var late = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        give(late, 0, Item.SPOOGE);
        p(late, 0).capacity = 4;
        p(late, 0).facing = Direction.RIGHT;
        run(late, 1, BOMB, IDLE);
        run(late, 13, IDLE, IDLE);
        run(late, 1, BOMB, IDLE);
        assertEquals(1, late.bombs.size(), "after 12 ticks a press is an ordinary press");
    }

    @Test void goldflameReachesAcrossTheArenaButNotThroughBlocks() {
        var rows = List.of("#########", "#.......#", "#.......#", "#.....B.#", "#.......#", "#.......#", "#########");
        var s = sim(rows, new Cell(1, 3), new Cell(7, 5));
        give(s, 0, Item.GOLDFLAME);
        run(s, 1, BOMB, IDLE);
        teleport(s, 0, 2, 5);
        idle(s, 150);
        assertTrue(flameCells(s).containsAll(List.of(new Cell(5, 3), new Cell(6, 3), new Cell(1, 5))));
        assertFalse(flameCells(s).contains(new Cell(7, 3)));
    }

    @Test void jellyBombBouncesOffWalls() {
        var s = sim(OPEN, new Cell(1, 5), new Cell(7, 5));
        give(s, 0, Item.JELLY);
        var b = bombAt(s, 0, 5, 1);
        assertTrue(b.jelly);
        roll(b, Direction.RIGHT);
        idle(s, 17); // 5 → 7 takes 16 ticks, then it turns at the wall
        assertEquals(Direction.LEFT, b.rolling);
        idle(s, 8 * 6 + 1); // six cells back, turning takes the tick it arrives
        assertEquals(Direction.RIGHT, b.rolling, "bounced again at the left wall");
        assertEquals(Simulation.BombState.ROLLING, b.state);
    }

    @Test void t16ConflictingPickupDropsTheOldAbilityDeterministically() {
        var s = sim(OPEN, new Cell(3, 3), new Cell(7, 5));
        give(s, 0, Item.PUNCH);
        s.items[3 * s.width + 3] = Item.TRIGGER;
        idle(s, 1);
        assertTrue(p(s, 0).has(Item.TRIGGER));
        assertFalse(p(s, 0).has(Item.PUNCH));
        assertEquals(Item.PUNCH, s.item(3, 2), "first free cell: radius 1, top row first");
        var b = bombAt(s, 0, 5, 1);
        assertTrue(b.trigger);
        teleport(s, 0, 3, 2);
        idle(s, 1);
        assertTrue(p(s, 0).has(Item.PUNCH));
        assertFalse(b.trigger, "losing Trigger gives its bombs a fuse");
        assertEquals(Item.TRIGGER, s.item(3, 1));
    }

    // ------------------------------------------------------------ Diseases

    @Test void t17DiseaseModifiesWithoutTouchingBaseStats() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        var p = p(s, 0);
        s.infect(p, Disease.SLOW, -1, 0);
        run(s, 60, go(Direction.RIGHT), IDLE);
        assertEquals(SUB / 2 + SUB * 3 / 2, p.x - SUB, 2, "1.5 cells in a second instead of 3");
        assertEquals(3000, p.speed);
        s.infect(p, Disease.FAST, -1, s.tick);
        assertEquals(List.of(Disease.FAST), p.effects.stream().map(Simulation.Effect::type).toList(), "opposites replace");
        s.items[p.z / SUB * s.width + p.x / SUB] = Item.BOMB_UP;
        idle(s, 1);
        assertTrue(p.effects.isEmpty(), "a good pickup cures the oldest effect");
        s.infect(p, Disease.SHORT_RANGE, -1, s.tick);
        idle(s, 601); // active during [start, start + 600)
        assertTrue(p.effects.isEmpty(), "every effect ends");
        assertEquals(3000, p.speed);
        assertEquals(2, p.range);
    }

    @Test void diseasedBombsLockTheirStatsAndNoBombsBeatsForcedBombs() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        var p = p(s, 0);
        s.infect(p, Disease.SHORT_RANGE, -1, 0);
        s.infect(p, Disease.SHORT_FUSE, -1, 0);
        run(s, 1, BOMB, IDLE);
        var b = s.bombs.getFirst();
        p.effects.clear();
        assertEquals(1, b.range);
        assertEquals(60, b.explodesAt);

        var t = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        t.infect(p(t, 0), Disease.FORCED_BOMBS, -1, 0);
        idle(t, 1);
        assertEquals(1, t.bombs.size(), "forced placement without pressing");
        var u = sim(OPEN, new Cell(1, 1), new Cell(7, 5));
        u.infect(p(u, 0), Disease.FORCED_BOMBS, -1, 0);
        u.infect(p(u, 0), Disease.NO_BOMBS, -1, 0);
        run(u, 5, BOMB, IDLE);
        assertTrue(u.bombs.isEmpty());
    }

    @Test void contactNeedsTouchingHitboxesAndRespectsTheCooldown() {
        var s = sim(OPEN, new Cell(1, 1), new Cell(3, 1));
        s.infect(p(s, 0), Disease.REVERSED, -1, 0);
        idle(s, 1);
        assertFalse(p(s, 1).sick(Disease.REVERSED), "a cell apart, no contact");
        p(s, 1).x = p(s, 0).x + SUB / 2;
        idle(s, 1);
        assertTrue(p(s, 1).sick(Disease.REVERSED));
        assertEquals(0, p(s, 1).effects.getFirst().source());
        p(s, 1).effects.clear();
        idle(s, 30);
        assertFalse(p(s, 1).sick(Disease.REVERSED), "cooldown of the pair");
        idle(s, 31);
        assertTrue(p(s, 1).sick(Disease.REVERSED));
    }

    // ------------------------------------------------------------ Teams and Kills mode

    @Test void teamDamageOffSparesTeammatesButNotTheOwnerOrEnemies() {
        var rules = RulesetDefinition.CLASSIC.with("teamDamage", false);
        var s = new Simulation(new ArenaLayout("test", OPEN), List.of(new Cell(1, 1), new Cell(3, 1), new Cell(1, 3), new Cell(7, 5)),
                List.of(0, 1, 0, 1), rules, 1);
        teleport(s, 0, 5, 5);
        s.flames.add(new Simulation.Flame(1, 3, 0, 10, 99, 0));
        s.flames.add(new Simulation.Flame(3, 1, 0, 10, 99, 0));
        idle(s, 1);
        assertTrue(p(s, 2).alive, "teammate spared");
        assertFalse(p(s, 1).alive, "enemy burns");
        assertEquals(1, s.kills(0));

        var on = new Simulation(new ArenaLayout("test", OPEN), List.of(new Cell(1, 1), new Cell(3, 1), new Cell(1, 3), new Cell(7, 5)),
                List.of(0, 1, 0, 1), RulesetDefinition.CLASSIC, 1);
        on.flames.add(new Simulation.Flame(1, 3, 0, 10, 99, 0));
        idle(on, 1);
        assertFalse(p(on, 2).alive);
        assertEquals(-1, on.kills(0), "a team kill costs a point");
        on.flames.add(new Simulation.Flame(7, 5, 1, 10, 98, 0));
        on.flames.add(new Simulation.Flame(3, 1, 1, 10, 98, 0));
        idle(on, 1);
        assertTrue(on.isOver());
        assertEquals(0, on.winner(), "the last team standing wins, though P1 of it is also alive");
    }

    @Test void killsModeEndsAtTheTickOfTheTargetAndATieIsADraw() {
        var arena = new ArenaLayout("test", OPEN);
        var rules = RulesetDefinition.CLASSIC.with("killsToWin", 1);
        var match = new Match(arena, List.of(new Cell(1, 1), new Cell(3, 1), new Cell(7, 5)), rules, 3);
        var round = match.nextRound();
        round.flames.add(new Simulation.Flame(3, 1, 0, 10, 99, 0));
        match.step(List.of(IDLE, IDLE, IDLE));
        assertFalse(round.isOver(), "two players still alive");
        assertEquals(0, match.champion());
        assertEquals(1, match.teamKills(0));
        assertThrows(IllegalStateException.class, () -> match.step(List.of(IDLE, IDLE, IDLE)));

        var tie = new Match(arena, List.of(new Cell(1, 1), new Cell(3, 1)), rules, 3);
        var duel = tie.nextRound();
        duel.flames.add(new Simulation.Flame(3, 1, 0, 10, 98, 0));
        duel.flames.add(new Simulation.Flame(1, 1, 0, 10, 99, 1));
        tie.step(List.of(IDLE, IDLE));
        assertEquals(Match.DRAW, tie.champion());
    }

    // ------------------------------------------------------------ Conveyor belts and teleports

    static final List<String> MECHANICS = List.of(
            "#########",
            "#.>>>>..#",
            "#.......#",
            "#.a...a.#",
            "#.......#",
            "#.......#",
            "#########");

    @Test void beltCarriesAStandingPlayerUntilItEndsAndSlowsWalkingAgainstIt() {
        var s = sim(MECHANICS, new Cell(1, 1), new Cell(7, 5));
        var p = p(s, 0);
        while (p.x < 2 * SUB + SUB / 2) run(s, 1, go(Direction.RIGHT), IDLE);
        idle(s, 200);
        assertEquals(6, p.x / SUB, "pushed along cells 2..5, stops once its centre leaves the belt");
        int before = p.x;
        idle(s, 30);
        assertEquals(before, p.x);
        teleport(s, 0, 4, 1);
        int start = p.x;
        run(s, 20, go(Direction.LEFT), IDLE);
        assertEquals(20 * (51 - 32), start - p.x, 20, "3 cells/s walking against 1.9 cells/s of belt");
    }

    @Test void bombOnABeltRidesToItsEndAndStops() {
        var s = sim(MECHANICS, new Cell(1, 5), new Cell(7, 5));
        var b = bombAt(s, 0, 3, 1);
        idle(s, 10);
        assertEquals(Simulation.BombState.ROLLING, b.state);
        assertNull(b.state == Simulation.BombState.ROLLING && b.kicker >= 0 ? b : null, "belts do not count as kicks");
        idle(s, 100);
        assertEquals(Simulation.BombState.PLACED, b.state);
        assertEquals(List.of(6, 1), List.of(b.x(), b.z()));
    }

    @Test void t18TeleportFiresOnEntryWithoutBouncingBack() {
        var s = sim(MECHANICS, new Cell(1, 3), new Cell(7, 5));
        var p = p(s, 0);
        boolean warped = false;
        for (int i = 0; i < 40 && !warped; i++) {
            run(s, 1, go(Direction.RIGHT), IDLE);
            warped = happened(s, SimulationEvent.Type.PLAYER_WARPED);
        }
        assertTrue(warped);
        assertEquals(List.of(6 * SUB + SUB / 2, 3 * SUB + SUB / 2), List.of(p.x, p.z), "arrives centred on the partner");
        idle(s, 100);
        assertEquals(6, p.x / SUB, "arriving is not entering: no ping-pong");
        run(s, 20, go(Direction.RIGHT), IDLE);
        boolean back = false;
        for (int i = 0; i < 40; i++) {
            run(s, 1, go(Direction.LEFT), IDLE);
            back |= happened(s, SimulationEvent.Type.PLAYER_WARPED);
        }
        assertTrue(back, "walking back onto the teleport after the cooldown warps again");
        assertTrue(p.x / SUB <= 2, "and the player is on the left side");
    }

    @Test void t18OccupiedExitCancelsAndFireAtTheExitHitsInTheSameTick() {
        var s = sim(MECHANICS, new Cell(1, 3), new Cell(7, 5));
        bombAt(s, 1, 6, 3);
        run(s, 40, go(Direction.RIGHT), IDLE);
        assertTrue(p(s, 0).x / SUB <= 2, "no transfer onto a bomb");
        assertTrue(p(s, 0).alive);

        var t = sim(MECHANICS, new Cell(1, 3), new Cell(7, 5));
        t.flames.add(new Simulation.Flame(6, 3, 0, 1000, 99, 1));
        for (int i = 0; i < 40 && p(t, 0).alive; i++) run(t, 1, go(Direction.RIGHT), IDLE);
        assertFalse(p(t, 0).alive);
        assertTrue(happened(t, SimulationEvent.Type.PLAYER_WARPED), "died in the tick of the warp");
        assertEquals(6, p(t, 0).x / SUB);
    }

    // ------------------------------------------------------------ Arena start items and item pools

    @Test void arenaStartItemsAndItemPoolAreHonoured() {
        var rows = List.of("#########", "#..BBBB.#", "#.#B#B#.#", "#..BBBB.#", "#########");
        var pool = List.of(Item.KICK, Item.KICK, Item.FLAME);
        var arena = new ArenaLayout("pool", "Pool", rows, List.of(), false, List.of(Item.FLAME, Item.SKATE, Item.GRAB), pool, java.util.Map.of(), "");
        var a = new Simulation(arena, List.of(new Cell(1, 1), new Cell(7, 3)), RulesetDefinition.CLASSIC, 5);
        for (var p : a.players) {
            assertEquals(3, p.range);
            assertEquals(3500, p.speed);
            assertTrue(p.has(Item.GRAB));
        }
        assertTrue(a.events().isEmpty(), "start items are not reported as pickups");
        var hidden = java.util.Arrays.stream(a.hidden).filter(java.util.Objects::nonNull).sorted().toList();
        assertEquals(List.of(Item.FLAME, Item.KICK, Item.KICK), hidden, "exactly the pool, no random extras");
        var b = new Simulation(arena, List.of(new Cell(1, 1), new Cell(7, 3)), RulesetDefinition.CLASSIC, 5);
        var c = new Simulation(arena, List.of(new Cell(1, 1), new Cell(7, 3)), RulesetDefinition.CLASSIC, 6);
        assertArrayEquals(a.hidden, b.hidden);
        assertFalse(java.util.Arrays.equals(a.hidden, c.hidden), "the seed shuffles which crates hold them");

        var crowded = new ArenaLayout("pool", "Pool", List.of("#####", "#.B.#", "#####"), List.of(), false, List.of(),
                java.util.Collections.nCopies(5, Item.BOMB_UP), java.util.Map.of(), "");
        var one = new Simulation(crowded, List.of(new Cell(1, 1), new Cell(3, 1)), RulesetDefinition.CLASSIC, 1);
        assertEquals(1, java.util.Arrays.stream(one.hidden).filter(java.util.Objects::nonNull).count(), "extra items are left out");

        var replay = new Replay(arena, List.of(new Cell(1, 1), new Cell(7, 3)), List.of(0, 1), RulesetDefinition.CLASSIC, 5,
                List.of(List.of(IDLE, IDLE)));
        var decoded = Replay.decode(replay.encode());
        assertEquals(arena.startItems(), decoded.arena().startItems());
        assertEquals(arena.itemPool(), decoded.arena().itemPool());
        assertEquals(replay.run(), decoded.run());
    }

    // ------------------------------------------------------------ Long run

    /**
     * Plan M4 acceptance: 100 rounds with every pickup and ability, random presses, holds and actions,
     * sudden death included. No bomb escapes the books, capacity bookkeeping holds, no effect is permanent.
     */
    @Test void hundredRoundsKeepBombsCapacityAndEffectsConsistent() {
        var rules = RulesetDefinition.CLASSIC.with("itemPercent", 100).with("roundTicks", 2400).with("suddenDeathTicks", 1200)
                .with("startAbilities", Item.KICK.bit() | Item.PUNCH.bit() | Item.GRAB.bit() | Item.JELLY.bit());
        var rows = new java.util.ArrayList<>(CLASSIC);
        rows.replaceAll(row -> row.replace('B', '?'));
        rows.set(7, "#?.?>>>>>>>?..?.#"); // a belt across the middle row
        rows.set(3, rows.get(3).substring(0, 5) + "a" + rows.get(3).substring(6));
        rows.set(9, rows.get(9).substring(0, 11) + "a" + rows.get(9).substring(12));
        var arena = new ArenaLayout("random", rows);
        var random = new java.util.Random(7);
        int lost = 0;
        var seen = java.util.EnumSet.noneOf(SimulationEvent.Type.class);
        for (int round = 0; round < 100; round++) {
            var s = new Simulation(arena, CLASSIC_STARTS, List.of(0, 1, 2, 3), rules, round);
            var held = new Direction[4];
            var holding = new boolean[4];
            java.util.Arrays.fill(held, Direction.NONE);
            while (!s.isOver()) {
                var frames = new java.util.ArrayList<PlayerInputFrame>();
                for (int i = 0; i < 4; i++) {
                    if (random.nextInt(15) == 0) held[i] = Direction.values()[random.nextInt(5)];
                    boolean press = random.nextInt(40) == 0;
                    if (press) holding[i] = true;
                    else if (random.nextInt(30) == 0) holding[i] = false;
                    frames.add(new PlayerInputFrame(held[i], press, holding[i], random.nextInt(50) == 0));
                }
                s.step(frames);
                lost += (int) s.events().stream().filter(e -> e.type() == SimulationEvent.Type.BOMB_LOST).count();
                s.events().forEach(e -> seen.add(e.type()));
                checkInvariants(s);
            }
        }
        assertTrue(lost < 100, "bombs without a landing place should be rare, got " + lost);
        assertTrue(seen.containsAll(java.util.EnumSet.complementOf(java.util.EnumSet.of(SimulationEvent.Type.BOMB_LOST))),
                "the run must exercise every mechanic, saw " + seen);
    }

    private static void checkInvariants(Simulation s) {
        for (var p : s.players) {
            assertEquals(s.bombs.stream().filter(b -> b.owner == p.id).count(), p.active, "capacity bookkeeping");
            assertTrue(p.active <= s.rules.maxBombs());
            for (var e : p.effects) assertTrue(e.end() >= s.tick && e.end() - e.start() == s.rules.diseaseTicks());
            if (p.carrying != null) {
                assertTrue(p.alive);
                assertEquals(Simulation.BombState.CARRIED, p.carrying.state);
                assertEquals(p.id, p.carrying.carrier);
            }
            if (!p.alive) continue;
            for (int x = (p.x - s.half) / SUB; x <= (p.x + s.half - 1) / SUB; x++) {
                for (int z = (p.z - s.half) / SUB; z <= (p.z + s.half - 1) / SUB; z++) {
                    assertEquals('.', s.blocks[z * s.width + x], "player inside a block");
                }
            }
        }
        int indexed = 0;
        for (var b : s.bombs) {
            boolean ground = b.state == Simulation.BombState.PLACED || b.state == Simulation.BombState.ROLLING;
            if (b.state == Simulation.BombState.PLACED) assertEquals(SUB / 2, b.px % SUB);
            if (b.state == Simulation.BombState.CARRIED) assertSame(b, s.players.get(b.carrier).carrying);
            int x0 = (b.px - SUB / 2) / SUB, x1 = (b.px + SUB / 2 - 1) / SUB, z0 = (b.pz - SUB / 2) / SUB, z1 = (b.pz + SUB / 2 - 1) / SUB;
            for (int z = z0; z <= z1 && ground; z++) {
                for (int x = x0; x <= x1; x++) {
                    assertSame(b, s.bombAt[z * s.width + x], "ground bomb indexed in every covered cell");
                    indexed++;
                }
            }
        }
        assertEquals(indexed, java.util.Arrays.stream(s.bombAt).filter(java.util.Objects::nonNull).count(), "no stale index entries");
    }
}
