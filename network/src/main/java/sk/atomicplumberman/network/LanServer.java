package sk.atomicplumberman.network;

import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.Match;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.Replay;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import sk.atomicplumberman.core.SimulationRunner;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

/**
 * Authoritative LAN host without graphics. It keeps the lobby, owns the only true {@link Match}, ticks it
 * at 60 Hz with the latest input of every seat and sends each tick's inputs to all clients, plus the
 * state hash every second. All state is guarded by {@code this}; socket threads only read and enqueue.
 */
public final class LanServer implements AutoCloseable {
    /** What the host chose in its menus; rules already include the arena's own changes. */
    public record Setup(ArenaLayout arena, RulesetDefinition rules, String theme, boolean teams) { }

    private enum Phase { LOBBY, COUNTDOWN, PLAYING, ROUND_OVER, MATCH_OVER }

    private final ServerSocket listener;
    private final DatagramSocket discovery;
    private final String name;
    private final long pauseNanos;
    private final List<Connection> connections = new ArrayList<>();
    private final List<SeatState> seats = new ArrayList<>();
    private final SimulationRunner runner = new SimulationRunner(8);
    private volatile boolean open = true;
    private Setup setup;
    private Phase phase = Phase.LOBBY;
    private long until;
    private int nextClient = 1;
    private Match match;
    private Simulation round;
    private List<SeatState> players = List.of();

    private static final class Connection {
        final Socket socket;
        final DataOutputStream out;
        int id;
        String name = "?";

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
    }

    private static final class SeatState {
        final Connection owner;
        final int local;
        final String label;
        boolean ready;
        boolean gone;
        Direction direction = Direction.NONE;
        boolean held;
        boolean bomb;
        boolean action;

        SeatState(Connection owner, int local, String label) {
            this.owner = owner;
            this.local = local;
            this.label = label;
        }

        Lan.Seat view() { return new Lan.Seat(owner.id, local, label, ready); }
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private LanServer(ServerSocket listener, DatagramSocket discovery, String name, long pauseNanos, Setup setup) {
        this.listener = listener;
        this.discovery = discovery;
        this.name = Lan.clip(name);
        this.pauseNanos = pauseNanos;
        this.setup = setup;
    }

    /**
     * Listens on {@code port} (0 picks a free one) and answers LAN searches when the discovery port is free.
     * {@code pauseNanos} is the countdown before and the result screen after a round.
     */
    public static LanServer start(int port, String name, Setup setup, long pauseNanos) throws IOException {
        var listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        DatagramSocket discovery = null;
        try {
            discovery = new DatagramSocket(null);
            discovery.setReuseAddress(true);
            discovery.bind(new InetSocketAddress(Lan.DISCOVERY_PORT));
        } catch (SocketException e) {
            if (discovery != null) discovery.close();
            discovery = null; // another host on this machine answers searches; joining by address still works
            System.err.println("LAN: discovery port " + Lan.DISCOVERY_PORT + " busy (" + e.getMessage() + ")");
        }
        var server = new LanServer(listener, discovery, name, pauseNanos, setup);
        server.thread("lan-accept", server::accept);
        server.thread("lan-loop", server::loop);
        if (discovery != null) server.thread("lan-discovery", server::answerSearches);
        return server;
    }

    public int port() { return listener.getLocalPort(); }

    private void thread(String threadName, Runnable body) {
        var thread = new Thread(body, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------ host commands

    /** New arena or rules from the host's lobby; everybody has to confirm ready again. */
    public synchronized void configure(Setup next) {
        if (phase != Phase.LOBBY) return;
        setup = next;
        while (seats.size() > capacity()) seats.removeLast();
        seats.forEach(s -> s.ready = false);
        broadcastLobby();
    }

    /** Same players again with a new seed; with somebody gone the lobby decides instead. */
    public synchronized void rematch() {
        if (phase != Phase.MATCH_OVER) return;
        if (players.stream().anyMatch(s -> s.gone)) toLobby();
        else startMatch(System.nanoTime());
    }

    public synchronized void toLobby() {
        runner.pause();
        phase = Phase.LOBBY;
        match = null;
        round = null;
        seats.removeIf(s -> s.gone);
        seats.forEach(s -> s.ready = false);
        broadcastLobby();
    }

    @Override public void close() {
        open = false;
        try {
            listener.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        if (discovery != null) discovery.close();
        synchronized (this) {
            for (var c : connections) closeQuietly(c.socket);
        }
    }

    // ------------------------------------------------------------ threads

    private void accept() {
        while (open) {
            try {
                var socket = listener.accept();
                socket.setTcpNoDelay(true);
                var connection = new Connection(socket);
                thread("lan-client", () -> serve(connection));
            } catch (IOException e) {
                if (open) System.err.println("LAN: accept failed (" + e.getMessage() + ")");
            }
        }
    }

    private void serve(Connection c) {
        try (c.socket) {
            c.socket.setSoTimeout(10_000); // a silent peer must say hello in time
            var in = new DataInputStream(new BufferedInputStream(c.socket.getInputStream()));
            if (in.readByte() != 'H') return;
            int protocol = in.readInt();
            c.name = Lan.clip(in.readUTF());
            c.socket.setSoTimeout(0);
            synchronized (this) {
                String refusal = protocol != Lan.PROTOCOL ? "Version mismatch: the host speaks protocol " + Lan.PROTOCOL + ", you " + protocol + "."
                        : phase != Phase.LOBBY ? "A match is in progress. Join when the host returns to the lobby."
                        : setup == null ? "The host has not chosen an arena yet." : null;
                if (refusal != null) {
                    send(c, out -> {
                        out.writeByte('X');
                        out.writeUTF(refusal);
                    });
                    return;
                }
                c.id = nextClient++;
                connections.add(c);
                send(c, out -> {
                    out.writeByte('W');
                    out.writeInt(c.id);
                });
                broadcastLobby();
            }
            while (open) {
                int type = in.readByte();
                switch (type) {
                    case 'J' -> {
                        int local = in.readUnsignedByte();
                        String device = Lan.clip(in.readUTF());
                        synchronized (this) { join(c, local, device); }
                    }
                    case 'R' -> {
                        int local = in.readUnsignedByte();
                        boolean ready = in.readBoolean();
                        synchronized (this) { ready(c, local, ready); }
                    }
                    case 'L' -> {
                        int local = in.readUnsignedByte();
                        synchronized (this) { leave(c, local); }
                    }
                    case 'I' -> {
                        int slot = in.readUnsignedByte();
                        var frame = Lan.unpack(in.readUnsignedByte());
                        synchronized (this) { input(c, slot, frame); }
                    }
                    default -> throw new IOException("unknown message " + type);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // the peer left or broke the protocol; either way it is dropped below
        } finally {
            synchronized (this) { drop(c); }
        }
    }

    private void loop() {
        while (open) {
            synchronized (this) { step(System.nanoTime()); }
            LockSupport.parkNanos(1_000_000);
        }
    }

    private void answerSearches() {
        var buffer = new byte[64];
        while (open) {
            try {
                var packet = new DatagramPacket(buffer, buffer.length);
                discovery.receive(packet);
                if (!new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).equals(Lan.DISCOVERY_QUERY)) continue;
                String reply;
                synchronized (this) {
                    reply = String.join(" ", Lan.DISCOVERY_REPLY, String.valueOf(Lan.PROTOCOL), String.valueOf(port()),
                            String.valueOf(seats.size()), String.valueOf(capacity()), phase == Phase.LOBBY ? "lobby" : "playing", name);
                }
                var bytes = reply.getBytes(StandardCharsets.UTF_8);
                discovery.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
            } catch (IOException e) {
                if (open) System.err.println("LAN: discovery failed (" + e.getMessage() + ")");
            }
        }
    }

    // ------------------------------------------------------------ lobby

    private int capacity() {
        return setup == null ? 0 : Math.min(Simulation.MAX_PLAYERS, setup.arena().starts().size());
    }

    private SeatState seat(Connection c, int local) {
        return seats.stream().filter(s -> s.owner == c && s.local == local).findFirst().orElse(null);
    }

    private void join(Connection c, int local, String device) {
        if (phase != Phase.LOBBY || seat(c, local) != null || seats.size() >= capacity()) return;
        seats.add(new SeatState(c, local, c.name + " · " + device));
        broadcastLobby();
    }

    private void ready(Connection c, int local, boolean ready) {
        var seat = seat(c, local);
        if (phase != Phase.LOBBY || seat == null) return;
        seat.ready = ready;
        broadcastLobby();
        if (seats.size() >= 2 && seats.stream().allMatch(s -> s.ready)) startMatch(System.nanoTime());
    }

    private void leave(Connection c, int local) {
        if (phase == Phase.LOBBY && seats.removeIf(s -> s.owner == c && s.local == local)) broadcastLobby();
    }

    private void drop(Connection c) {
        closeQuietly(c.socket);
        if (!connections.remove(c)) return;
        // Seats of a departed client stand still for the rest of the match (neutral input) and leave with the lobby.
        for (var s : players) if (s.owner == c) s.gone = true;
        boolean changed = seats.removeIf(s -> s.owner == c);
        if (changed && phase == Phase.LOBBY) broadcastLobby();
    }

    private void input(Connection c, int slot, PlayerInputFrame frame) {
        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN || slot >= players.size() || players.get(slot).owner != c) return;
        var s = players.get(slot);
        s.direction = frame.direction();
        s.held = frame.bombHeld();
        s.bomb |= frame.bomb();     // presses wait for the next tick, however short they were
        s.action |= frame.action();
    }

    // ------------------------------------------------------------ match

    private void startMatch(long seed) {
        players = List.copyOf(seats);
        int n = players.size();
        var arena = setup.arena();
        boolean teamPlay = (arena.teams() || setup.teams()) && n > 2;
        var teams = IntStream.range(0, n).map(i -> teamPlay ? i % 2 : i).boxed().toList();
        var starts = arena.starts().subList(0, n);
        match = new Match(arena, starts, teams, setup.rules(), seed);
        String header = new Replay(arena, starts, teams, setup.rules(), seed, List.of()).encode();
        var views = players.stream().map(SeatState::view).toList();
        broadcast(out -> {
            out.writeByte('M');
            out.writeUTF(header);
            out.writeUTF(setup.theme());
            out.writeBoolean(teamPlay);
            writeSeats(out, views);
        });
        nextRound();
    }

    private void nextRound() {
        round = match.nextRound();
        for (var s : players) {
            s.bomb = false;
            s.action = false;
        }
        int number = match.rounds();
        broadcast(out -> {
            out.writeByte('N');
            out.writeInt(number);
        });
        phase = Phase.COUNTDOWN;
        until = System.nanoTime() + pauseNanos;
    }

    private void step(long now) {
        switch (phase) {
            case COUNTDOWN -> {
                if (now - until >= 0) {
                    phase = Phase.PLAYING;
                    runner.resume(now);
                }
            }
            case PLAYING -> {
                runner.advance(now, this::tick);
                if (runner.consumeStall()) { // the host cannot pause for everybody; carry on from now
                    System.err.println("LAN: host fell behind, resuming at tick " + round.tick());
                    runner.resume(now);
                }
            }
            case ROUND_OVER -> {
                if (now - until >= 0) nextRound();
            }
            default -> { }
        }
    }

    private void tick() {
        var frames = new ArrayList<PlayerInputFrame>();
        var packed = new byte[players.size()];
        for (int i = 0; i < players.size(); i++) {
            var s = players.get(i);
            var frame = s.gone ? PlayerInputFrame.NEUTRAL : new PlayerInputFrame(s.direction, s.bomb, s.held, s.action);
            s.bomb = false;
            s.action = false;
            frames.add(frame);
            packed[i] = (byte) Lan.pack(frame);
        }
        long tick = round.tick();
        match.step(frames);
        boolean over = round.isOver() || match.isOver();
        long hash = round.stateHash();
        long after = round.tick();
        broadcast(out -> {
            out.writeByte('F');
            out.writeLong(tick);
            out.writeByte(packed.length);
            out.write(packed);
            if (over || after % Replay.HASH_INTERVAL == 0) {
                out.writeByte('S');
                out.writeLong(after);
                out.writeLong(hash);
            }
        });
        if (!over) return;
        runner.pause();
        phase = match.isOver() ? Phase.MATCH_OVER : Phase.ROUND_OVER;
        until = System.nanoTime() + pauseNanos;
    }

    // ------------------------------------------------------------ sending

    private void broadcastLobby() {
        String arena = setup == null ? "" : setup.arena().name();
        int capacity = capacity();
        var views = seats.stream().map(SeatState::view).toList();
        broadcast(out -> {
            out.writeByte('B');
            out.writeUTF(arena);
            out.writeByte(capacity);
            writeSeats(out, views);
        });
    }

    private static void writeSeats(DataOutputStream out, List<Lan.Seat> seats) throws IOException {
        out.writeByte(seats.size());
        for (var s : seats) {
            out.writeInt(s.client());
            out.writeByte(s.local());
            out.writeUTF(s.label());
            out.writeBoolean(s.ready());
        }
    }

    private void broadcast(Writer writer) {
        for (var c : List.copyOf(connections)) send(c, writer);
    }

    // ponytail: writes under the server lock; a stuck peer delays others only until TCP gives up. Per-client queues if that bites.
    private void send(Connection c, Writer writer) {
        try {
            writer.write(c.out);
            c.out.flush();
        } catch (IOException e) {
            closeQuietly(c.socket); // its reader thread notices and drops it
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
