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

    @Test void bindingATakenKeySwapsInsteadOfDuplicating(@TempDir Path dir) {
        var settings = Settings.load(dir);
        settings.bind(0, 4, KeyInput.KEY_W); // keyboard 1 bomb takes keyboard 2's up
        assertEquals(KeyInput.KEY_W, settings.keys[0][4]);
        assertEquals(KeyInput.KEY_SPACE, settings.keys[1][0]);
        settings.bind(0, 0, KeyInput.KEY_DOWN); // within one profile: up and down trade places
        assertEquals(KeyInput.KEY_DOWN, settings.keys[0][0]);
        assertEquals(KeyInput.KEY_UP, settings.keys[0][1]);
    }
}
