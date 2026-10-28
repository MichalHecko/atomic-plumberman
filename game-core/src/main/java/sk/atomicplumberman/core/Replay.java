package sk.atomicplumberman.core;

import java.util.ArrayList;
import java.util.List;

/** Everything needed to reproduce one round bit for bit; text form for files and other processes. */
public record Replay(ArenaLayout arena, List<Cell> starts, List<Integer> teams, RulesetDefinition rules, long seed,
        List<List<PlayerInputFrame>> ticks) {
    private static final String HEADER = "atomic-plumberman-replay 4";
    private static final String DIRECTIONS = ".UDLR";
    public static final int HASH_INTERVAL = 60;

    public Replay {
        starts = List.copyOf(starts);
        teams = List.copyOf(teams);
        ticks = ticks.stream().map(List::copyOf).toList();
    }

    /** Replays headless; returns the state hash every {@link #HASH_INTERVAL} ticks and finally at the end. */
    public List<Long> run() {
        var simulation = new Simulation(arena, starts, teams, rules, seed);
        var hashes = new ArrayList<Long>();
        for (var frames : ticks) {
            simulation.step(frames);
            if (simulation.tick() % HASH_INTERVAL == 0) hashes.add(simulation.stateHash());
        }
        hashes.add(simulation.stateHash());
        return hashes;
    }

    public String encode() {
        var out = new StringBuilder(HEADER).append('\n')
                .append("rules ").append(ruleValues(rules)).append('\n')
                .append("seed ").append(seed).append('\n')
                .append("arena ").append(arena.id()).append('\n');
        arena.rows().forEach(row -> out.append("row ").append(row).append('\n'));
        arena.startItems().forEach(item -> out.append("startitem ").append(item).append('\n'));
        arena.itemPool().forEach(item -> out.append("poolitem ").append(item).append('\n'));
        starts.forEach(s -> out.append("start ").append(s.x()).append(' ').append(s.z()).append('\n'));
        out.append("teams");
        teams.forEach(t -> out.append(' ').append(t));
        out.append('\n');
        for (var frames : ticks) {
            out.append('t');
            for (var f : frames) {
                out.append(' ').append(DIRECTIONS.charAt(f.direction().ordinal()));
                if (f.bomb()) out.append('b');
                if (f.bombHeld()) out.append('h');
                if (f.action()) out.append('a');
            }
            out.append('\n');
        }
        return out.toString();
    }

    // Reflection keeps the format in step with every ruleset field; order is the record's declaration order.
    private static String ruleValues(RulesetDefinition rules) {
        var values = new StringBuilder();
        for (var component : RulesetDefinition.class.getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(rules);
                if (value instanceof List<?> list) value = String.join(",", list.stream().map(String::valueOf).toList());
                values.append(values.isEmpty() ? "" : " ").append(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return values.toString();
    }

    private static RulesetDefinition parseRules(String[] parts) {
        var components = RulesetDefinition.class.getRecordComponents();
        if (parts.length - 1 != components.length) throw new IllegalArgumentException("expected " + components.length + " values");
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int k = 0; k < components.length; k++) {
            types[k] = components[k].getType();
            String text = parts[k + 1];
            if (types[k] == boolean.class) {
                if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("expected true/false: " + text);
                values[k] = Boolean.parseBoolean(text);
            } else if (types[k] == List.class) {
                values[k] = java.util.Arrays.stream(text.split(",")).map(Integer::valueOf).toList();
            } else {
                values[k] = Integer.parseInt(text);
            }
        }
        try {
            return RulesetDefinition.class.getDeclaredConstructor(types).newInstance(values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Replay decode(String text) {
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) throw new IllegalArgumentException("line 1: expected " + HEADER);
        RulesetDefinition rules = null;
        Long seed = null;
        String arenaId = null;
        var rows = new ArrayList<String>();
        var starts = new ArrayList<Cell>();
        var teams = new ArrayList<Integer>();
        var startItems = new ArrayList<Item>();
        var itemPool = new ArrayList<Item>();
        var ticks = new ArrayList<List<PlayerInputFrame>>();
        for (int i = 1; i < lines.size(); i++) {
            var parts = lines.get(i).split(" ");
            try {
                switch (parts[0]) {
                    case "rules" -> rules = parseRules(parts);
                    case "seed" -> seed = Long.parseLong(parts[1]);
                    case "arena" -> arenaId = parts[1];
                    case "row" -> rows.add(parts[1]);
                    case "start" -> starts.add(new Cell(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                    case "startitem" -> startItems.add(Item.valueOf(parts[1]));
                    case "poolitem" -> itemPool.add(Item.valueOf(parts[1]));
                    case "teams" -> {
                        for (int k = 1; k < parts.length; k++) teams.add(Integer.parseInt(parts[k]));
                    }
                    case "t" -> {
                        var frames = new ArrayList<PlayerInputFrame>();
                        for (int k = 1; k < parts.length; k++) {
                            String token = parts[k];
                            int dir = DIRECTIONS.indexOf(token.charAt(0));
                            String flags = token.substring(1);
                            if (dir < 0 || !flags.matches("b?h?a?")) throw new IllegalArgumentException("bad frame " + token);
                            frames.add(new PlayerInputFrame(Direction.values()[dir], flags.contains("b"), flags.contains("h"),
                                    flags.contains("a")));
                        }
                        ticks.add(frames);
                    }
                    default -> throw new IllegalArgumentException("unknown key " + parts[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (rules == null || seed == null || arenaId == null) throw new IllegalArgumentException("replay: missing rules, seed or arena");
        if (teams.size() != starts.size()) throw new IllegalArgumentException("replay: expected one team per start");
        return new Replay(new ArenaLayout(arenaId, arenaId, rows, List.of(), false, startItems, itemPool, java.util.Map.of(), ""), starts, teams, rules, seed, ticks);
    }
}
