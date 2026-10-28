package sk.atomicplumberman.content;

import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.Match;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.Replay;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plan 12.3 and 12.5: whole matches on every arena in every mode (wins, kills, teams, sudden death) with
 * random inputs and seeds and every slot taken. Checks invariants each tick, that rounds and matches end,
 * and measures the step time; a failing round is saved as a replay. Ends with a load scene of ten players
 * with eight bombs each. Exit codes: 0 ok, 1 failure, 2 usage.
 */
public final class Soak {
    /** Rounds per match: enough for Wins matches to end often while 1000 rounds still visit every arena in every mode. */
    static final int MAX_ROUNDS = 5;

    record Result(int matches, int rounds, int draws, long ticks, long p95Nanos, long maxNanos, int maxBombs, List<String> failures) { }

    private Soak() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: soak <arenas directory> <rounds> <failure replay directory>");
            System.exit(2);
        }
        var arenas = readArenas(Path.of(args[0]));
        long start = System.nanoTime();
        var result = run(arenas, Integer.parseInt(args[1]), MAX_ROUNDS, Path.of(args[2]), System.out);
        var load = load(arenas.stream().filter(a -> a.id().equals("classic-wide")).findFirst().orElseThrow(), 10);
        System.out.printf("SOAK %d matches, %d rounds, %d ticks in %.0f s, %.1f %% draws, %.0f ticks per round;"
                        + " step p95 %.3f ms, max %.3f ms; heap %d MB%n",
                result.matches(), result.rounds(), result.ticks(), (System.nanoTime() - start) / 1e9, 100.0 * result.draws() / result.rounds(),
                (double) result.ticks() / result.rounds(), result.p95Nanos() / 1e6, result.maxNanos() / 1e6, usedHeapMb());
        System.out.printf("LOAD 10 players, up to %d bombs at once: step p95 %.3f ms, max %.3f ms%n",
                load.maxBombs(), load.p95Nanos() / 1e6, load.maxNanos() / 1e6);
        result.failures().forEach(f -> System.err.println("FAILED " + f));
        load.failures().forEach(f -> System.err.println("FAILED load: " + f));
        if (!result.failures().isEmpty() || !load.failures().isEmpty()) System.exit(1);
    }

    static List<ArenaLayout> readArenas(Path directory) throws IOException {
        var arenas = new ArrayList<ArenaLayout>();
        for (String id : Files.readAllLines(directory.resolve("index.txt"))) {
            if (id.isBlank()) continue;
            try (var reader = Files.newBufferedReader(directory.resolve(id.strip() + ".json"), StandardCharsets.UTF_8)) {
                arenas.add(ArenaJson.read(reader, id));
            }
        }
        return arenas;
    }

    /** Plays matches of at most {@code maxRounds} rounds until {@code rounds} rounds are done, cycling arenas and modes. */
    static Result run(List<ArenaLayout> arenas, int rounds, int maxRounds, Path failures, PrintStream log) throws IOException {
        var times = new ArrayList<long[]>();
        var failed = new ArrayList<String>();
        int played = 0;
        int draws = 0;
        long ticks = 0;
        int maxBombs = 0;
        long baselineHeap = -1;
        int matchNo = 0;
        for (; played < rounds; matchNo++) {
            var arena = arenas.get(matchNo % arenas.size());
            int mode = matchNo / arenas.size() % 4; // 0 wins, 1 kills, 2 teams, 3 kills with teams
            boolean suddenDeath = matchNo / (arenas.size() * 4) % 2 == 0;
            int players = arena.starts().size();
            boolean teamPlay = (arena.teams() || mode >= 2) && players > 2;
            var teams = java.util.stream.IntStream.range(0, players).map(i -> teamPlay ? i % 2 : i).boxed().toList();
            var rules = arena.rules(RulesetDefinition.CLASSIC
                    .with("roundTicks", 45 * Simulation.TICKS_PER_SECOND) // sudden death from 15 s
                    .with("killsToWin", mode % 2 == 1 ? 3 : 0)
                    .with("suddenDeathTicks", suddenDeath ? RulesetDefinition.CLASSIC.suddenDeathTicks() : 0));
            long seed = matchNo * 7919L + 1;
            var match = new Match(arena, arena.starts(), teams, rules, seed);
            var random = new Random(seed);
            boolean careful = random.nextBoolean(); // fewer bombs: longer rounds that reach sudden death
            String label = arena.id() + " mode " + mode + (suddenDeath ? " sudden death" : "") + " seed " + seed;
            while (!match.isOver() && match.rounds() < maxRounds && played < rounds) {
                var round = match.nextRound();
                var recording = new ArrayList<List<PlayerInputFrame>>();
                var held = new Direction[players];
                var holding = new boolean[players];
                Arrays.fill(held, Direction.NONE);
                var stepTimes = new long[rules.roundTicks() + 1];
                int steps = 0;
                try {
                    while (!round.isOver() && !match.isOver()) {
                        if (round.tick() > rules.roundTicks()) throw new IllegalStateException("round outlived its time");
                        var frames = randomFrames(random, held, holding, careful ? 400 : 40);
                        recording.add(frames);
                        long t0 = System.nanoTime();
                        match.step(frames);
                        stepTimes[steps++] = System.nanoTime() - t0;
                        maxBombs = Math.max(maxBombs, round.bombs().size());
                        check(round);
                    }
                } catch (RuntimeException error) {
                    Files.createDirectories(failures);
                    var file = failures.resolve(arena.id() + "-" + round.seed() + ".replay");
                    Files.writeString(file, new Replay(arena, arena.starts(), teams, rules, round.seed(), recording).encode());
                    failed.add(label + " round " + match.rounds() + " tick " + round.tick() + ": " + error + " (replay " + file + ")");
                    break;
                }
                times.add(Arrays.copyOf(stepTimes, steps));
                ticks += round.tick();
                played++;
                if (round.winner() < 0) draws++;
            }
            if (matchNo % 20 == 19) {
                long heap = usedHeapMb();
                if (baselineHeap < 0) baselineHeap = heap;
                else if (heap > baselineHeap + 64) failed.add("heap grew from " + baselineHeap + " to " + heap + " MB");
                if (log != null) log.printf("... %d rounds, heap %d MB%n", played, heap);
            }
        }
        var all = times.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        return new Result(matchNo, played, draws, ticks, all[(int) (all.length * .95)], all[all.length - 1], maxBombs, failed);
    }

    /** Ten players with eight bombs, range 8 and a 10 s fuse from the start, pressing often: the 12.5 load scene. */
    static Result load(ArenaLayout arena, int rounds) {
        var rules = RulesetDefinition.CLASSIC.with("startBombs", 8).with("startRange", 8).with("fuseTicks", 600).with("fillPercent", 0)
                .with("roundTicks", 60 * Simulation.TICKS_PER_SECOND).with("suddenDeathTicks", 0);
        var times = new ArrayList<Long>();
        var failed = new ArrayList<String>();
        int maxBombs = 0;
        for (int r = 0; r < rounds; r++) {
            var round = new Simulation(arena, arena.starts(), rules, r);
            var random = new Random(r);
            var held = new Direction[arena.starts().size()];
            Arrays.fill(held, Direction.NONE);
            var holding = new boolean[held.length];
            try {
                while (!round.isOver()) {
                    var frames = randomFrames(random, held, holding, 4);
                    long t0 = System.nanoTime();
                    round.step(frames);
                    times.add(System.nanoTime() - t0);
                    maxBombs = Math.max(maxBombs, round.bombs().size());
                    check(round);
                }
            } catch (RuntimeException error) {
                failed.add("round " + r + " tick " + round.tick() + ": " + error);
            }
        }
        long[] all = times.stream().mapToLong(Long::longValue).sorted().toArray();
        return new Result(1, rounds, 0, all.length, all[(int) (all.length * .95)], all[all.length - 1], maxBombs, failed);
    }

    private static List<PlayerInputFrame> randomFrames(Random random, Direction[] held, boolean[] holding, int pressOneIn) {
        var frames = new ArrayList<PlayerInputFrame>(held.length);
        for (int i = 0; i < held.length; i++) {
            if (random.nextInt(15) == 0) held[i] = Direction.values()[random.nextInt(5)];
            boolean press = random.nextInt(pressOneIn) == 0;
            if (press) holding[i] = true;
            else if (random.nextInt(30) == 0) holding[i] = false;
            frames.add(new PlayerInputFrame(held[i], press, holding[i], random.nextInt(50) == 0));
        }
        return frames;
    }

    /** Invariants visible through the public API; the core's own tests check the private bookkeeping. */
    static void check(Simulation s) {
        int half = s.rules().hitbox() / 2;
        int[] owned = new int[s.players().size()];
        for (var b : s.bombs()) {
            owned[b.owner()]++;
            if (b.x() < 0 || b.z() < 0 || b.x() >= s.width() || b.z() >= s.height()) {
                throw new IllegalStateException("bomb " + b.id() + " outside the arena");
            }
        }
        for (var p : s.players()) {
            if (owned[p.id()] > s.rules().maxBombs()) throw new IllegalStateException("P" + (p.id() + 1) + " over the bomb limit");
            for (var e : p.effects()) {
                if (e.end() < s.tick()) throw new IllegalStateException("P" + (p.id() + 1) + " kept an expired " + e.type());
            }
            if (!p.alive()) continue;
            for (int x = (p.x() - half) / Simulation.SUB; x <= (p.x() + half - 1) / Simulation.SUB; x++) {
                for (int z = (p.z() - half) / Simulation.SUB; z <= (p.z() + half - 1) / Simulation.SUB; z++) {
                    if (s.block(x, z) != '.') throw new IllegalStateException("P" + (p.id() + 1) + " inside a block at " + x + "," + z);
                }
            }
        }
    }

    private static long usedHeapMb() {
        System.gc();
        var runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) >> 20;
    }
}
