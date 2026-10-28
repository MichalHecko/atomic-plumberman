package sk.atomicplumberman.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable grid: {@code #} solid, {@code B} destructible, {@code ?} destructible by seed chance,
 * {@code .} always free, {@code < > ^ v} free conveyor belts, letters {@code a}–{@code z} except {@code v}
 * free teleports that come in pairs of the same letter. Starts are free cells in slot order. An arena may require team
 * play (slots alternate between two sides), give every player items at the start and fix the items hidden
 * under its crates ({@code itemPool}, empty means the ruleset's random items). {@code rules} adjusts ruleset
 * values the layout was designed for, applied by {@link #rules(RulesetDefinition)}. {@code name} and
 * {@code theme} are for menus and presentation only; the simulation ignores them.
 */
public record ArenaLayout(String id, String name, List<String> rows, List<Cell> starts, boolean teams,
        List<Item> startItems, List<Item> itemPool, Map<String, Integer> rules, String theme) {
    public ArenaLayout {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("id: expected a lowercase identifier");
        }
        Objects.requireNonNull(rows, "rows");
        rows = List.copyOf(rows);
        starts = List.copyOf(Objects.requireNonNull(starts, "starts"));
        startItems = List.copyOf(startItems);
        itemPool = List.copyOf(itemPool);
        rules = Map.copyOf(rules);
        RulesetDefinition check = RulesetDefinition.CLASSIC;
        for (var entry : rules.entrySet()) check = check.with(entry.getKey(), entry.getValue()); // unknown or invalid values fail here
        name = name == null || name.isBlank() ? id : name;
        theme = theme == null ? "" : theme;
        for (Item a : startItems) {
            for (Item b : startItems) {
                if (a.conflictsWith(b)) throw new IllegalArgumentException("startItems: " + a + " conflicts with " + b);
            }
        }
        if (rows.size() < 3 || rows.size() > 128) {
            throw new IllegalArgumentException("rows: height must be 3..128");
        }
        int width = rows.getFirst().length();
        if (width < 3 || width > 128) {
            throw new IllegalArgumentException("rows[0]: width must be 3..128");
        }
        for (int z = 0; z < rows.size(); z++) {
            String row = rows.get(z);
            if (row.length() != width) {
                throw new IllegalArgumentException("rows[" + z + "]: inconsistent width");
            }
            for (int x = 0; x < width; x++) {
                char cell = row.charAt(x);
                if ("#.B?<>^v".indexOf(cell) < 0 && (cell < 'a' || cell > 'z')) {
                    throw new IllegalArgumentException("rows[" + z + "][" + x + "]: unknown cell " + cell);
                }
                if ((z == 0 || z == rows.size() - 1 || x == 0 || x == width - 1) && cell != '#') {
                    throw new IllegalArgumentException("rows[" + z + "][" + x + "]: border must be solid");
                }
            }
        }
        var warps = new int[26];
        rows.forEach(row -> row.chars().filter(c -> c >= 'a' && c <= 'z' && c != 'v').forEach(c -> warps[c - 'a']++));
        for (int i = 0; i < warps.length; i++) {
            if (warps[i] != 0 && warps[i] != 2) {
                throw new IllegalArgumentException("rows: teleport " + (char) ('a' + i) + " must appear exactly twice");
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            Cell s = starts.get(i);
            if (s.x() < 0 || s.z() < 0 || s.x() >= width || s.z() >= rows.size() || rows.get(s.z()).charAt(s.x()) != '.') {
                throw new IllegalArgumentException("starts[" + i + "]: expected a free cell");
            }
            if (starts.subList(0, i).contains(s)) throw new IllegalArgumentException("starts[" + i + "]: duplicate");
        }
    }

    public ArenaLayout(String id, List<String> rows) { this(id, rows, List.of()); }
    public ArenaLayout(String id, List<String> rows, List<Cell> starts) {
        this(id, id, rows, starts, false, List.of(), List.of(), Map.of(), "");
    }

    /** The given ruleset with this arena's adjustments, e.g. a starting flame range the layout needs. */
    public RulesetDefinition rules(RulesetDefinition base) {
        var result = base;
        for (var entry : new java.util.TreeMap<>(rules).entrySet()) result = result.with(entry.getKey(), entry.getValue());
        return result;
    }

    public int width() { return rows.getFirst().length(); }
    public int height() { return rows.size(); }

    public char cell(int x, int z) {
        Objects.checkIndex(x, width());
        Objects.checkIndex(z, height());
        return rows.get(z).charAt(x);
    }
}
