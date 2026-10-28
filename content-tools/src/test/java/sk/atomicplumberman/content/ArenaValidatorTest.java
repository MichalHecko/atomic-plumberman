package sk.atomicplumberman.content;

import org.junit.jupiter.api.Test;
import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.RulesetDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArenaValidatorTest {
    private static List<String> check(boolean teams, String... rows) {
        var starts = List.of(new Cell(1, 1), new Cell(rows[0].length() - 2, rows.length - 2));
        return ArenaValidator.problems(new ArenaLayout("t", "t", List.of(rows), starts, teams, List.of(), List.of(), java.util.Map.of(), ""), RulesetDefinition.CLASSIC);
    }

    @Test void everyBundledArenaPassesTheGameplayChecks() throws Exception {
        try (var files = Files.list(Path.of("../assets/runtime/Data/arenas"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try (var reader = Files.newBufferedReader(file)) {
                    var arena = ArenaJson.read(reader, file.toString());
                    assertEquals(List.of(), ArenaValidator.problems(arena, RulesetDefinition.CLASSIC), file.toString());
                }
            }
        }
    }

    @Test void theMenuIndexListsEveryArenaOnce() throws Exception {
        var index = Files.readAllLines(Path.of("../assets/runtime/Data/arenas/index.txt"));
        try (var files = Files.list(Path.of("../assets/runtime/Data/arenas"))) {
            var ids = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
            assertEquals(ids, index.stream().sorted().toList());
            assertEquals(index.size(), new java.util.HashSet<>(index).size());
        }
    }

    @Test void aStartWithoutEscapeIsRejected() {
        var problems = check(false, "#######", "#...??#", "#?????#", "#???..#", "#######");
        assertTrue(problems.contains("starts[0]: no escape from an own bomb before it explodes"), problems.toString());
    }

    @Test void disconnectedOpponentsAreRejectedUnlessTeamsCanThrowAcross() {
        String[] split = {"###########", "#....#....#", "#....#....#", "#....#....#", "###########"};
        assertTrue(check(false, split).stream().anyMatch(p -> p.contains("not connected")));
        assertEquals(List.of(), check(true, split), "a three-cell throw crosses the wall");
        // Even wrapping over the edge, three cells never reach the single right column.
        var far = List.of("###############", "#....#####.####", "#....#####.####", "#....#####.####", "###############");
        var problems = ArenaValidator.problems(new ArenaLayout("t", "t", far, List.of(new Cell(1, 1), new Cell(10, 3)), true, List.of(), List.of(), java.util.Map.of(), ""),
                RulesetDefinition.CLASSIC);
        assertTrue(problems.stream().anyMatch(p -> p.contains("throw")), problems.toString());
    }
}
