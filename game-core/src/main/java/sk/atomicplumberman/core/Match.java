package sk.atomicplumberman.core;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Rounds of one match; wins and kills carry over and each round gets a seed derived from the match seed.
 * Scores belong to teams (in free-for-all every slot is its own team). In the Kills mode the match is
 * decided at the end of the tick in which a team reaches the target; reaching it together is a draw.
 */
public final class Match {
    public static final int ONGOING = -1;
    public static final int DRAW = -2;
    private final ArenaLayout arena;
    private final List<Cell> starts;
    private final List<Integer> teams;
    private final RulesetDefinition rules;
    private final long seed;
    private final int[] wins;
    private final int[] kills;
    private int rounds;
    private int result = ONGOING;
    private boolean tallied;
    private Simulation round;

    public Match(ArenaLayout arena, List<Cell> starts, RulesetDefinition rules, long seed) {
        this(arena, starts, IntStream.range(0, starts.size()).boxed().toList(), rules, seed);
    }

    public Match(ArenaLayout arena, List<Cell> starts, List<Integer> teams, RulesetDefinition rules, long seed) {
        this.arena = arena;
        this.starts = List.copyOf(starts);
        this.teams = List.copyOf(teams);
        this.rules = rules;
        this.seed = seed;
        wins = new int[starts.size()];
        kills = new int[starts.size()];
    }

    public Simulation nextRound() {
        if (round != null && !round.isOver()) throw new IllegalStateException("round in progress");
        if (isOver()) throw new IllegalStateException("match is over");
        round = new Simulation(arena, starts, teams, rules, Simulation.splitMix(seed + ++rounds * Simulation.GAMMA));
        tallied = false;
        return round;
    }

    /** Steps the current round; results are tallied in the tick that ends it. */
    public void step(List<PlayerInputFrame> inputs) {
        if (isOver()) throw new IllegalStateException("match is over");
        round.step(inputs);
        if (rules.killsToWin() > 0) {
            int leaders = 0;
            int leader = ONGOING;
            for (int team = 0; team < kills.length; team++) {
                if (teamKills(team) >= rules.killsToWin()) {
                    leaders++;
                    leader = team;
                }
            }
            if (leaders > 0) result = leaders == 1 ? leader : DRAW;
        }
        if (!round.isOver() && !isOver()) return;
        tallied = true;
        if (round.isOver() && round.winner() >= 0) wins[round.winner()]++;
        for (int i = 0; i < kills.length; i++) kills[i] += round.kills(i);
        if (rules.killsToWin() == 0) {
            for (int team = 0; team < wins.length; team++) {
                if (wins[team] >= rules.winsToWin()) result = team;
            }
        }
    }

    /** Champion team, {@link #ONGOING} or {@link #DRAW}. */
    public int champion() { return result; }
    public boolean isOver() { return result != ONGOING; }
    public int rounds() { return rounds; }
    public int team(int slot) { return teams.get(slot); }
    public int wins(int team) { return wins[team]; }
    /** Kills of a slot, including the round in progress. */
    public int kills(int slot) { return kills[slot] + (round != null && !tallied ? round.kills(slot) : 0); }

    /** Kills of a team, including the round in progress. */
    public int teamKills(int team) {
        int total = 0;
        for (int slot = 0; slot < kills.length; slot++) {
            if (teams.get(slot) == team) total += kills(slot);
        }
        return total;
    }
}
