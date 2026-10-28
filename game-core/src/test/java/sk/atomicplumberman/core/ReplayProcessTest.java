package sk.atomicplumberman.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** T20: the same replay gives the same hashes and result in separate JVM processes. */
class ReplayProcessTest {
    public static void main(String[] args) throws IOException {
        System.out.println(Replay.decode(Files.readString(Path.of(args[0]))).run());
    }

    @Test void separateProcessesProduceIdenticalHashes(@TempDir Path dir) throws Exception {
        var replay = SimulationTest.randomReplay(20);
        var file = Files.writeString(dir.resolve("round.replay"), replay.encode());
        String expected = replay.run().toString();
        for (int i = 0; i < 2; i++) {
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ReplayProcessTest.class.getName(), file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            assertEquals(0, process.waitFor(), output);
            assertEquals(expected, output);
        }
    }
}
