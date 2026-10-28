package sk.atomicplumberman.network;

import org.junit.jupiter.api.Test;
import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.Match;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LanTest {
    private static final ArenaLayout ARENA = new ArenaLayout("open", List.of(
            "#########",
            "#.......#",
            "#.#.#.#.#",
            "#.......#",
            "#.#.#.#.#",
            "#.......#",
            "#########"), List.of(new Cell(1, 1), new Cell(7, 5), new Cell(7, 1), new Cell(1, 5)));
    private static final RulesetDefinition RULES = RulesetDefinition.CLASSIC.with("suddenDeathTicks", 0).with("roundTicks", 120)
            .with("fillPercent", 0);

    /** A client's copy of the match, built only from what the host sent, as the desktop client does. */
    private static final class Mirror {
        final LanClient client;
        final List<String> problems = new ArrayList<>();
        Lan.Lobby lobby;
        Lan.MatchStart start;
        Match match;
        Simulation round;
        final List<Simulation> finished = new ArrayList<>();
        int frames;
        int hashes;
        String closed;

        Mirror(LanClient client) { this.client = client; }

        void pump() {
            for (var m = client.poll(); m != null; m = client.poll()) {
                switch (m) {
                    case Lan.Lobby l -> lobby = l;
                    case Lan.MatchStart s -> {
                        start = s;
                        var r = s.setup();
                        match = new Match(r.arena(), r.starts(), r.teams(), r.rules(), r.seed());
                    }
                    case Lan.Round r -> {
                        if (round != null) finished.add(round);
                        round = match.nextRound();
                        if (match.rounds() != r.number()) problems.add("round " + r.number() + " but mirror at " + match.rounds());
                    }
                    case Lan.Frame f -> {
                        if (f.tick() != round.tick()) problems.add("frame for tick " + f.tick() + " at " + round.tick());
                        match.step(f.inputs());
                        frames++;
                    }
                    case Lan.Hash h -> {
                        hashes++;
                        if (h.tick() != round.tick() || h.hash() != round.stateHash()) problems.add("desync at tick " + h.tick());
                    }
                    case Lan.Closed c -> closed = c.reason();
                    case Lan.Welcome w -> { }
                }
            }
        }
    }

    private static void await(BooleanSupplier condition, Mirror... mirrors) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            for (var m : mirrors) m.pump();
            assertTrue(System.nanoTime() < end, "timed out");
            Thread.sleep(2);
        }
    }

    private static LanServer server() throws Exception {
        return LanServer.start(0, "test host", new LanServer.Setup(ARENA, RULES, "bm-tiles", false), 0);
    }

    @Test void twoClientsAndThreeSeatsPlayTheSameRoundAsTheHost() throws Exception {
        try (var server = server();
                var a = LanClient.connect("127.0.0.1", server.port(), "alpha");
                var b = LanClient.connect("127.0.0.1", server.port(), "beta")) {
            var ma = new Mirror(a);
            var mb = new Mirror(b);
            await(() -> a.id() > 0 && b.id() > 0, ma, mb);
            a.join(0, "Keyboard A"); // two local players on one connection
            a.join(1, "Keyboard B");
            await(() -> mb.lobby != null && mb.lobby.seats().size() == 2, ma, mb); // seats follow join order across machines
            b.join(5, "Gamepad 1");
            await(() -> mb.lobby != null && mb.lobby.seats().size() == 3, ma, mb);
            assertEquals(List.of("alpha · Keyboard A", "alpha · Keyboard B", "beta · Gamepad 1"),
                    mb.lobby.seats().stream().map(Lan.Seat::label).toList());
            a.ready(0, true);
            a.ready(1, true);
            b.ready(5, true);
            await(() -> ma.round != null && mb.round != null, ma, mb);
            assertEquals(3, mb.start.players().size());
            assertFalse(mb.start.teamPlay());
            // Inputs from both machines; frames are only applied in the order the host decided.
            a.input(0, new PlayerInputFrame(Direction.RIGHT, true));
            b.input(2, new PlayerInputFrame(Direction.LEFT, false, false, false));
            b.input(0, new PlayerInputFrame(Direction.DOWN, true)); // not b's seat: ignored
            // The next round follows on its own: the time limit ends the first one in a draw.
            await(() -> !ma.finished.isEmpty() && !mb.finished.isEmpty(), ma, mb);
            assertEquals(List.of(), ma.problems);
            assertEquals(List.of(), mb.problems);
            var first = ma.finished.getFirst();
            assertEquals(120, first.tick());
            assertTrue(ma.hashes >= 2, "hash every second and at the end");
            assertEquals(first.stateHash(), mb.finished.getFirst().stateHash());
            assertTrue(first.players().get(0).x() > Simulation.SUB * 3 / 2, "P1 walked right");
            assertEquals(Simulation.SUB * 15 / 2, first.players().get(1).x(), "b may not steer P2");
        }
    }

    @Test void aLeavingClientsSeatsStandStillAndTheOthersPlayOn() throws Exception {
        try (var server = server(); var a = LanClient.connect("127.0.0.1", server.port(), "alpha")) {
            var ma = new Mirror(a);
            var b = LanClient.connect("127.0.0.1", server.port(), "beta");
            var mb = new Mirror(b);
            await(() -> a.id() > 0 && b.id() > 0, ma, mb);
            a.join(0, "Keyboard A");
            b.join(0, "Keyboard A");
            a.ready(0, true);
            b.ready(0, true);
            await(() -> ma.round != null && mb.round != null, ma, mb);
            b.input(1, new PlayerInputFrame(Direction.UP, false, false, false));
            await(() -> mb.frames > 10, ma, mb);
            b.close();
            int seen = ma.frames;
            await(() -> ma.frames > seen + 30, ma);
            int z = ma.round.players().get(1).z();
            await(() -> ma.frames > seen + 60, ma);
            assertEquals(z, ma.round.players().get(1).z(), "a departed seat gets neutral input");
            assertEquals(List.of(), ma.problems);
            assertNull(ma.closed);
        }
    }

    @Test void wrongVersionAndGarbageAreRefusedWithoutHurtingTheHost() throws Exception {
        try (var server = server()) {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                var out = new DataOutputStream(socket.getOutputStream());
                out.writeByte('H');
                out.writeInt(Lan.PROTOCOL + 1);
                out.writeUTF("future");
                var in = new java.io.DataInputStream(socket.getInputStream());
                assertEquals('X', in.readByte());
                assertTrue(in.readUTF().startsWith("Version mismatch"));
            }
            try (var garbage = LanClient.connect("127.0.0.1", server.port(), "bad")) {
                var mg = new Mirror(garbage);
                await(() -> garbage.id() > 0, mg);
                var raw = garbage.getClass().getDeclaredField("out");
                raw.setAccessible(true);
                var out = (DataOutputStream) raw.get(garbage);
                out.writeByte('I');
                out.writeByte(0);
                out.writeByte(255); // not a valid input byte
                out.flush();
                await(() -> mg.closed != null, mg);
            }
            try (var ok = LanClient.connect("127.0.0.1", server.port(), "fine")) {
                var mo = new Mirror(ok);
                await(() -> mo.lobby != null, mo);
                assertEquals("open", mo.lobby.arena());
                assertEquals(4, mo.lobby.capacity());
            }
        }
    }

    @Test void aSearchFindsTheHost() throws Exception {
        try (var server = server()) {
            var found = LanClient.discover(500);
            assertTrue(found.stream().anyMatch(h -> h.port() == server.port() && h.name().equals("test host") && h.inLobby()
                    && h.capacity() == 4), "found " + found);
        }
    }

    @Test void inputBytesRoundTrip() {
        for (int packed = 0; packed < 64; packed++) {
            if ((packed & 7) < Direction.values().length) assertEquals(packed, Lan.pack(Lan.unpack(packed)));
        }
        assertThrows(IllegalArgumentException.class, () -> Lan.unpack(7));
        assertThrows(IllegalArgumentException.class, () -> Lan.unpack(64));
    }
}
