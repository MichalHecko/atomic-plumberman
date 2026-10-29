package sk.atomicplumberman.desktop;

import com.jme3.input.KeyInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void roundTripsAndLeavesNoTemporaryFiles(@TempDir Path dir) throws Exception {
        var settings = Settings.load(dir);
        settings.winsToWin = 5;
        settings.suddenDeath = false;
        settings.keys[1][4] = KeyInput.KEY_LCONTROL;
        settings.save();
        var loaded = Settings.load(dir);
        assertEquals(5, loaded.winsToWin);
        assertFalse(loaded.suddenDeath);
        assertEquals(KeyInput.KEY_LCONTROL, loaded.keys[1][4]);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test void invalidValuesFallBackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.properties"),
                "match.winsToWin=42\nmatch.roundSeconds=abc\naudio.volume=-5\nkeys.1.bomb=NO_SUCH_KEY\nkeys.2.up=I\n");
        var loaded = Settings.load(dir);
        assertEquals(3, loaded.winsToWin);
        assertEquals(150, loaded.roundSeconds);
        assertEquals(80, loaded.volume);
        assertEquals(KeyInput.KEY_SPACE, loaded.keys[0][4]);
        assertEquals(KeyInput.KEY_I, loaded.keys[1][0]);
    }

    @Test void rulesRoundTripAndDefaultToTheClassicGameAtOneAndAHalfTimesTheSpeed(@TempDir Path dir) throws Exception {
        var settings = Settings.load(dir);
        var classic = sk.atomicplumberman.core.RulesetDefinition.CLASSIC;
        var defaults = MatchState.rules(MatchState.loadArena("classic"), settings);
        assertEquals(classic.speed() * 3 / 2, defaults.speed());
        assertEquals(classic.with("speed", defaults.speed()), defaults);
        settings.rules.put("fuseTicks", 90);
        settings.save();
        assertEquals(90, Settings.load(dir).rules.get("fuseTicks"));
        Files.writeString(dir.resolve("settings.properties"), "rules.rollSpeed=100\nrules.startBombs=x\n");
        var loaded = Settings.load(dir);
        assertEquals(classic.rollSpeed(), loaded.rules.get("rollSpeed"), "not a divisor of a cell, so not a choice");
        assertEquals(classic.startBombs(), loaded.rules.get("startBombs"));
    }

    @Test void everyRuleChoiceMakesAValidRulesetInEveryArena(@TempDir Path dir) {
        var settings = Settings.load(dir);
        for (var rule : Settings.RULES) {
            for (int choice : rule.choices()) {
                settings.resetRules();
                settings.rules.put(rule.component(), choice);
                for (String arena : Settings.ARENAS) {
                    assertDoesNotThrow(() -> MatchState.rules(MatchState.loadArena(arena), settings), rule.component() + "=" + choice + " in " + arena);
                }
            }
            assertEquals(rule.choices()[1], rule.step(rule.choices()[0], 1));
            assertEquals(rule.choices()[0], rule.step(rule.choices()[0], -1), "stops at the end");
        }
    }

    @Test void bindingATakenKeySwapsInsteadOfDuplicating(@TempDir Path dir) {
        var settings = Settings.load(dir);
        settings.bind(0, 4, KeyInput.KEY_W); // keyboard 1 bomb takes keyboard 2's up
        assertEquals(KeyInput.KEY_W, settings.keys[0][4]);
        assertEquals(KeyInput.KEY_SPACE, settings.keys[1][0]);
        settings.bind(0, 0, KeyInput.KEY_DOWN); // within one profile: up and down trade places
        assertEquals(KeyInput.KEY_DOWN, settings.keys[0][0]);
        assertEquals(KeyInput.KEY_UP, settings.keys[0][1]);
    }

    @Test void randomArenaSurvivesSavingAndFitsThePlayers(@TempDir Path dir) throws Exception {
        var settings = Settings.load(dir);
        settings.arena = Settings.RANDOM;
        settings.save();
        assertEquals(Settings.RANDOM, Settings.load(dir).arena);
        int most = MatchState.maxPlayers(Settings.RANDOM);
        for (long seed = 0; seed < 20; seed++) {
            String picked = MatchState.pickArena(Settings.RANDOM, most, seed);
            assertTrue(Settings.ARENAS.contains(picked));
            assertEquals(most, MatchState.loadArena(picked).starts().size());
        }
        assertEquals("classic", MatchState.pickArena("classic", 2, 0));
    }
}
