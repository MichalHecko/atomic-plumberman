package sk.atomicplumberman.desktop;

import com.jme3.input.KeyInput;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Local player preferences and match options, stored as a properties file in the user's config
 * directory. Invalid or missing values fall back to defaults; the file is replaced atomically.
 */
final class Settings {
    static final String[] ACTIONS = {"up", "down", "left", "right", "bomb", "action"};
    static final int[][] DEFAULT_KEYS = {
            {KeyInput.KEY_UP, KeyInput.KEY_DOWN, KeyInput.KEY_LEFT, KeyInput.KEY_RIGHT, KeyInput.KEY_SPACE, KeyInput.KEY_RETURN},
            {KeyInput.KEY_W, KeyInput.KEY_S, KeyInput.KEY_A, KeyInput.KEY_D, KeyInput.KEY_F, KeyInput.KEY_G}};

    final Path directory;
    /** Arena ids in menu order, from Data/arenas/index.txt. */
    static final java.util.List<String> ARENAS = readIndex();
    /** Picks one of {@link #ARENAS} for each match, see {@link MatchState#pickArena}. */
    static final String RANDOM = "random";
    /** The menu's arena choices: all arenas, then {@link #RANDOM}. */
    static final java.util.List<String> ARENA_CHOICES =
            java.util.stream.Stream.concat(ARENAS.stream(), java.util.stream.Stream.of(RANDOM)).toList();
    /** "map" uses the arena's own theme (Workshop when it has none). */
    static final java.util.List<String> THEMES = java.util.List.of("map",
            "bm-tiles", "bm-meadow", "bm-volcano", "bm-warehouse", "bm-binary", "bm-lab", "bm-jungle");
    String arena = "classic";
    String theme = "map";
    int musicVolume = 60;
    int effectsVolume = 90;
    boolean cameraShake = true;
    boolean flicker = true;
    boolean killsMode;
    int killsToWin = 5;
    boolean teams;
    boolean teamDamage = true;
    int winsToWin = 3;
    int roundSeconds = 150;
    boolean suddenDeath = true;
    int volume = 80;
    /** Per keyboard profile: up, down, left, right, bomb, action as jME key codes. */
    final int[][] keys = {DEFAULT_KEYS[0].clone(), DEFAULT_KEYS[1].clone()};

    /**
     * A ruleset value the Rules menu tunes: the {@link RulesetDefinition} component, its choices in menu order
     * (the default among them) and how a value reads. Arenas with their own value for it still win.
     */
    record Rule(String component, String label, String hint, int fallback, int[] choices, IntFunction<String> show) {
        Rule {
            choices = IntStream.concat(Arrays.stream(choices), IntStream.of(fallback)).sorted().distinct().toArray();
        }

        /** The choice {@code steps} away from {@code value}, stopping at the ends. */
        int step(int value, int steps) {
            int at = Arrays.binarySearch(choices, value);
            if (at < 0) at = -at - 1;
            return choices[Math.clamp(at + steps, 0, choices.length - 1)];
        }
    }

    private static final RulesetDefinition CLASSIC = RulesetDefinition.CLASSIC;
    static final List<Rule> RULES = List.of(
            new Rule("speed", "Starting speed", "How fast everyone walks before any speed-up.",
                    CLASSIC.speed() * 3 / 2, range(1500, 6000, 500), v -> decimal(v / 1000.0) + " cells/s"),
            new Rule("startBombs", "Starting bombs", "Bombs each player may have on the ground at once.",
                    CLASSIC.startBombs(), range(1, CLASSIC.maxBombs(), 1), String::valueOf),
            new Rule("fuseTicks", "Bomb fuse", "Time from placing a bomb to its explosion.",
                    CLASSIC.fuseTicks(), range(60, 300, 15), Settings::seconds),
            new Rule("flameTicks", "Flame duration", "How long the fire of an explosion keeps burning.",
                    CLASSIC.flameTicks(), range(12, 60, 3), Settings::seconds),
            new Rule("fillPercent", "Crates", "Share of the arena's random crate cells that get a crate; Bombman maps keep theirs.",
                    CLASSIC.fillPercent(), range(0, 100, 10), v -> v + " %"),
            new Rule("itemPercent", "Pickups in crates", "Chance that a crate hides a pickup.",
                    CLASSIC.itemPercent(), range(0, 100, 10), v -> v + " %"),
            new Rule("rollSpeed", "Kick speed", "How fast a kicked bomb rolls.",
                    CLASSIC.rollSpeed(), new int[] {32, 64, 128, 256}, v -> decimal(v * Simulation.TICKS_PER_SECOND / (double) Simulation.SUB) + " cells/s"),
            new Rule("cornerAssist", "Corner assist", "How far off a lane you may be and still slide around a corner.",
                    CLASSIC.cornerAssist(), range(0, 512, 32), v -> v * 100 / Simulation.SUB + " % of a cell"),
            new Rule("flameTolerance", "Flame forgiveness", "How deep you may brush into fire unharmed.",
                    CLASSIC.flameTolerance(), range(0, CLASSIC.hitbox() / 2 - 1, 32), v -> v * 100 / Simulation.SUB + " % of a cell"),
            new Rule("turnBufferTicks", "Turn buffer", "How long a turn pressed early waits for the corner.",
                    CLASSIC.turnBufferTicks(), range(0, 18, 1), v -> v * 1000 / Simulation.TICKS_PER_SECOND + " ms"),
            new Rule("diseaseTicks", "Disease duration", "How long a skull's effect lasts.",
                    CLASSIC.diseaseTicks(), range(300, 1800, 60), Settings::seconds));
    /** Current value of each of {@link #RULES}, by component. */
    final Map<String, Integer> rules = new LinkedHashMap<>();

    private Settings(Path directory) {
        this.directory = directory;
        resetRules();
    }

    void resetRules() {
        for (var rule : RULES) rules.put(rule.component(), rule.fallback());
    }

    private static int[] range(int from, int to, int step) {
        return IntStream.iterate(from, v -> v <= to, v -> v + step).toArray();
    }

    private static String decimal(double value) {
        return new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String seconds(int ticks) { return decimal(ticks / (double) Simulation.TICKS_PER_SECOND) + " s"; }

    private static java.util.List<String> readIndex() {
        try (var stream = java.util.Objects.requireNonNull(Settings.class.getClassLoader().getResourceAsStream("Data/arenas/index.txt"))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** XDG config dir on Linux, %APPDATA% on Windows; {@code override} wins when given. */
    static Path defaultDirectory(String override) {
        if (override != null) return Path.of(override);
        String appData = System.getenv("APPDATA");
        if (appData != null && System.getProperty("os.name", "").startsWith("Windows")) return Path.of(appData, "AtomicPlumberman");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("atomic-plumberman");
    }

    static Settings load(Path directory) {
        var settings = new Settings(directory);
        var file = directory.resolve("settings.properties");
        if (!Files.exists(file)) return settings;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var properties = new Properties();
            properties.load(reader);
            String arena = properties.getProperty("match.arena", settings.arena);
            if (ARENA_CHOICES.contains(arena)) settings.arena = arena;
            else System.err.println("SETTINGS: unknown arena " + arena + ", keeping " + settings.arena);
            String theme = properties.getProperty("display.theme", settings.theme);
            if (THEMES.contains(theme)) settings.theme = theme;
            else System.err.println("SETTINGS: unknown theme " + theme + ", keeping " + settings.theme);
            settings.musicVolume = number(properties, "audio.music", 0, 100, settings.musicVolume);
            settings.effectsVolume = number(properties, "audio.effects", 0, 100, settings.effectsVolume);
            settings.cameraShake = !"false".equals(properties.getProperty("display.cameraShake"));
            settings.flicker = !"false".equals(properties.getProperty("display.flicker"));
            settings.killsMode = "kills".equals(properties.getProperty("match.mode"));
            settings.killsToWin = number(properties, "match.killsToWin", 1, 20, settings.killsToWin);
            settings.teams = "true".equals(properties.getProperty("match.teams"));
            settings.teamDamage = !"false".equals(properties.getProperty("match.teamDamage"));
            settings.winsToWin = number(properties, "match.winsToWin", 1, 9, settings.winsToWin);
            settings.roundSeconds = number(properties, "match.roundSeconds", 60, 300, settings.roundSeconds) / 30 * 30;
            settings.suddenDeath = !"false".equals(properties.getProperty("match.suddenDeath"));
            settings.volume = number(properties, "audio.volume", 0, 100, settings.volume);
            for (var rule : RULES) {
                String key = "rules." + rule.component();
                int value = number(properties, key, Integer.MIN_VALUE, Integer.MAX_VALUE, rule.fallback());
                if (Arrays.binarySearch(rule.choices(), value) >= 0) settings.rules.put(rule.component(), value);
                else System.err.println("SETTINGS: " + key + " must be one of " + Arrays.toString(rule.choices()) + ", keeping " + rule.fallback());
            }
            for (int profile = 0; profile < 2; profile++) {
                for (int action = 0; action < ACTIONS.length; action++) {
                    String name = properties.getProperty("keys." + (profile + 1) + "." + ACTIONS[action]);
                    int code = name == null ? -1 : keyCode(name);
                    if (code >= 0) settings.keys[profile][action] = code;
                    else if (name != null) System.err.println("SETTINGS: unknown key " + name + ", keeping default");
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("SETTINGS: cannot read " + file + " (" + exception.getMessage() + "), using defaults");
        }
        return settings;
    }

    private static int number(Properties properties, String key, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // falls through to the warning below
        }
        System.err.println("SETTINGS: " + key + " must be " + min + ".." + max + ", keeping " + fallback);
        return fallback;
    }

    void save() throws IOException {
        var properties = new Properties();
        properties.setProperty("match.arena", arena);
        properties.setProperty("display.theme", theme);
        properties.setProperty("audio.music", String.valueOf(musicVolume));
        properties.setProperty("audio.effects", String.valueOf(effectsVolume));
        properties.setProperty("display.cameraShake", String.valueOf(cameraShake));
        properties.setProperty("display.flicker", String.valueOf(flicker));
        properties.setProperty("match.mode", killsMode ? "kills" : "wins");
        properties.setProperty("match.killsToWin", String.valueOf(killsToWin));
        properties.setProperty("match.teams", String.valueOf(teams));
        properties.setProperty("match.teamDamage", String.valueOf(teamDamage));
        properties.setProperty("match.winsToWin", String.valueOf(winsToWin));
        properties.setProperty("match.roundSeconds", String.valueOf(roundSeconds));
        properties.setProperty("match.suddenDeath", String.valueOf(suddenDeath));
        properties.setProperty("audio.volume", String.valueOf(volume));
        rules.forEach((component, value) -> properties.setProperty("rules." + component, String.valueOf(value)));
        for (int profile = 0; profile < 2; profile++) {
            for (int action = 0; action < ACTIONS.length; action++) {
                properties.setProperty("keys." + (profile + 1) + "." + ACTIONS[action], keyName(keys[profile][action]));
            }
        }
        var text = new StringWriter();
        properties.store(text, "Atomic Plumberman settings; key names are jME KeyInput constants without KEY_");
        writeAtomically(directory.resolve("settings.properties"), text.toString());
    }

    /** Binds a key to one keyboard action; where the key was already bound, the old key takes its place. */
    void bind(int profile, int action, int code) {
        int old = keys[profile][action];
        for (int[] profileKeys : keys) {
            for (int i = 0; i < profileKeys.length; i++) if (profileKeys[i] == code) profileKeys[i] = old;
        }
        keys[profile][action] = code;
    }

    /** Readers see either the old or the new file, never a half-written one. */
    static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static int keyCode(String name) {
        try {
            var field = KeyInput.class.getField("KEY_" + name.trim().toUpperCase(Locale.ROOT));
            return Modifier.isStatic(field.getModifiers()) && field.getType() == int.class ? field.getInt(null) : -1;
        } catch (ReflectiveOperationException exception) {
            return -1;
        }
    }

    static String keyName(int code) {
        return Arrays.stream(KeyInput.class.getFields())
                .filter(f -> f.getName().startsWith("KEY_") && f.getType() == int.class)
                .filter(f -> {
                    try {
                        return f.getInt(null) == code;
                    } catch (IllegalAccessException exception) {
                        return false;
                    }
                })
                .map(f -> f.getName().substring(4)).findFirst().orElse(String.valueOf(code));
    }
}
