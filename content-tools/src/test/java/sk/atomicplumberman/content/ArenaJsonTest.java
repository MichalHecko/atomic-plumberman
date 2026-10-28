package sk.atomicplumberman.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.StringReader;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.Item;
import static org.junit.jupiter.api.Assertions.*;

class ArenaJsonTest {
    @Test void validDefinitionCrossesTheJsonBoundary() {
        var arena = ArenaJson.read(new StringReader("""
                {"schemaVersion":2,"id":"test","starts":[[1,1],[3,1]],"rows":["#####","#.B.#","#####"]}
                """), "test.json");
        assertEquals(5, arena.width());
        assertEquals('B', arena.cell(2, 1));
        assertEquals(java.util.List.of(new Cell(1, 1), new Cell(3, 1)), arena.starts());
        assertFalse(arena.teams());
        var duel = ArenaJson.read(new StringReader("""
                {"schemaVersion":2,"id":"duel","starts":[[1,1],[3,1]],"rows":["#####","#.#.#","#####"],
                 "teams":true,"startItems":["GRAB","FLAME","FLAME"],"itemPool":{"KICK":2,"BOMB_UP":1},
                 "name":"Duel","theme":"garden"}
                """), "duel.json");
        assertTrue(duel.teams());
        assertEquals(java.util.List.of(Item.GRAB, Item.FLAME, Item.FLAME), duel.startItems());
        assertEquals(3, duel.itemPool().size());
        assertEquals(java.util.List.of("Duel", "garden"), java.util.List.of(duel.name(), duel.theme()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{bad json", "{\"schemaVersion\":1}",
            "{\"schemaVersion\":2,\"id\":42}", "{\"schemaVersion\":2,\"id\":\"test\",\"rows\":[null]}",
            "{\"schemaVersion\":2,\"id\":\"test\",\"rows\":[\"###\",\"#.#\",\"###\"],\"starts\":[[1,1]]}",
            "{\"schemaVersion\":2,\"id\":\"test\",\"rows\":[\"####\",\"#..#\",\"####\"],\"starts\":[[1,1],[1.5,1]]}",
            "{\"schemaVersion\":2,\"id\":\"test\",\"rows\":[\"####\",\"#..#\",\"####\"],\"starts\":[[1,1],[0,0]]}",
            "{\"schemaVersion\":2,\"id\":\"t\",\"rows\":[\"####\",\"#..#\",\"####\"],\"starts\":[[1,1],[2,1]],\"startItems\":[\"PUNCH\",\"TRIGGER\"]}",
            "{\"schemaVersion\":2,\"id\":\"t\",\"rows\":[\"####\",\"#..#\",\"####\"],\"starts\":[[1,1],[2,1]],\"itemPool\":{\"LAVA\":1}}",
            "{\"schemaVersion\":2,\"id\":\"t\",\"rows\":[\"####\",\"#..#\",\"####\"],\"starts\":[[1,1],[2,1]],\"teams\":\"yes\"}"})
    void invalidContentReportsItsSource(String json) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ArenaJson.read(new StringReader(json), "broken.json"));
        assertTrue(error.getMessage().startsWith("broken.json:"));
    }

    @Test void validationReportsTheOffendingRow() {
        var error = assertThrows(IllegalArgumentException.class, () -> ArenaJson.read(new StringReader("""
                {"schemaVersion":2,"id":"test","starts":[[1,1],[1,1]],"rows":["###","#%#","###"]}
                """), "broken.json"));
        assertTrue(error.getMessage().contains("rows[1][1]"));
    }
}
