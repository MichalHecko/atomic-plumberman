package sk.atomicplumberman.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Plan M7 mode matrix: every bundled arena in Wins, Kills, teams and sudden death, plus the load scene. */
class SoakTest {
    @Test void everyArenaAndModeFinishesRoundsWithoutBreakingInvariants(@TempDir Path failures) throws Exception {
        var arenas = Soak.readArenas(Path.of("../assets/runtime/Data/arenas"));
        var result = Soak.run(arenas, 600, 3, failures, null);
        assertEquals(List.of(), result.failures());
        assertEquals(600, result.rounds());
        assertTrue(result.matches() >= arenas.size() * 8, "each arena in all 4 modes, with and without sudden death");
        var load = Soak.load(arenas.stream().filter(a -> a.id().equals("classic-wide")).findFirst().orElseThrow(), 2);
        assertEquals(List.of(), load.failures());
        assertTrue(load.maxBombs() >= 40, "the load scene must fill the arena with bombs, got " + load.maxBombs());
    }
}
