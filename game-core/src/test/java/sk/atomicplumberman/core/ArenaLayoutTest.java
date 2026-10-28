package sk.atomicplumberman.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ArenaLayoutTest {
    @Test void layoutCannotBeChangedByItsCaller() {
        var rows = new ArrayList<>(List.of("#####", "#.B.#", "#####"));
        var arena = new ArenaLayout("preview", rows);
        rows.set(1, "#####");
        assertEquals('.', arena.cell(1, 1));
        assertEquals('B', arena.cell(2, 1));
        assertThrows(UnsupportedOperationException.class, () -> arena.rows().clear());
        assertThrows(IndexOutOfBoundsException.class, () -> arena.cell(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> arena.cell(5, 1));
    }

    static Stream<List<String>> invalidLayouts() {
        return Stream.of(List.of("###", "#.#"), List.of("###", "#..#", "###"),
                List.of("###", "#%#", "###"), List.of("#.#", "#.#", "###"));
    }

    @ParameterizedTest @MethodSource("invalidLayouts")
    void malformedOrOpenArenasAreRejected(List<String> rows) {
        assertThrows(IllegalArgumentException.class, () -> new ArenaLayout("preview", rows));
    }
}
