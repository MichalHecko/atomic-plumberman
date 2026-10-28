package sk.atomicplumberman.network;

import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.Replay;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** One game instance's connection to a host; the host's own players use one too. Thread safe. */
public final class LanClient implements AutoCloseable {
    /** A host that answered a LAN search. */
    public record Host(String address, int port, String name, int seats, int capacity, boolean inLobby) { }

    private final Socket socket;
    private final DataOutputStream out;
    private final ConcurrentLinkedQueue<Lan.Message> inbox = new ConcurrentLinkedQueue<>();
    private volatile boolean closing;
    private volatile int id;

    private LanClient(Socket socket) throws IOException {
        this.socket = socket;
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public static LanClient connect(String host, int port, String name) throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        var client = new LanClient(socket);
        client.send(o -> {
            o.writeByte('H');
            o.writeInt(Lan.PROTOCOL);
            o.writeUTF(Lan.clip(name));
        });
        var reader = new Thread(client::read, "lan-reader");
        reader.setDaemon(true);
        reader.start();
        return client;
    }

    /** Our id from the host's welcome, 0 before it arrives. */
    public int id() { return id; }

    /** Next message from the host in arrival order, or null. A lost connection ends with {@link Lan.Closed}. */
    public Lan.Message poll() { return inbox.poll(); }

    public void join(int local, String device) {
        send(o -> {
            o.writeByte('J');
            o.writeByte(local);
            o.writeUTF(Lan.clip(device));
        });
    }

    public void ready(int local, boolean ready) {
        send(o -> {
            o.writeByte('R');
            o.writeByte(local);
            o.writeBoolean(ready);
        });
    }

    public void leave(int local) {
        send(o -> {
            o.writeByte('L');
            o.writeByte(local);
        });
    }

    public void input(int slot, PlayerInputFrame frame) {
        send(o -> {
            o.writeByte('I');
            o.writeByte(slot);
            o.writeByte(Lan.pack(frame));
        });
    }

    @Override public void close() {
        closing = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private synchronized void send(Writer writer) {
        try {
            writer.write(out);
            out.flush();
        } catch (IOException e) {
            try {
                socket.close(); // the reader reports the loss
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private void read() {
        String reason = "The host closed the game.";
        try (socket) {
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (true) {
                int type = in.readByte();
                switch (type) {
                    case 'W' -> {
                        id = in.readInt();
                        inbox.add(new Lan.Welcome(id));
                    }
                    case 'X' -> {
                        reason = in.readUTF();
                        return;
                    }
                    case 'B' -> {
                        String arena = in.readUTF();
                        int capacity = in.readUnsignedByte();
                        inbox.add(new Lan.Lobby(arena, capacity, readSeats(in)));
                    }
                    case 'M' -> {
                        var setup = Replay.decode(in.readUTF());
                        String theme = in.readUTF();
                        boolean teamPlay = in.readBoolean();
                        inbox.add(new Lan.MatchStart(setup, theme, teamPlay, readSeats(in)));
                    }
                    case 'N' -> inbox.add(new Lan.Round(in.readInt()));
                    case 'F' -> {
                        long tick = in.readLong();
                        var frames = new ArrayList<PlayerInputFrame>();
                        for (int i = in.readUnsignedByte(); i > 0; i--) frames.add(Lan.unpack(in.readUnsignedByte()));
                        inbox.add(new Lan.Frame(tick, frames));
                    }
                    case 'S' -> inbox.add(new Lan.Hash(in.readLong(), in.readLong()));
                    default -> throw new IOException("unknown message " + type);
                }
            }
        } catch (EOFException e) {
            // the host closed the connection
        } catch (IOException | IllegalArgumentException e) {
            reason = "Connection lost: " + e.getMessage();
        } finally {
            if (!closing) inbox.add(new Lan.Closed(reason));
        }
    }

    private static List<Lan.Seat> readSeats(DataInputStream in) throws IOException {
        var seats = new ArrayList<Lan.Seat>();
        for (int i = in.readUnsignedByte(); i > 0; i--) {
            seats.add(new Lan.Seat(in.readInt(), in.readUnsignedByte(), in.readUTF(), in.readBoolean()));
        }
        return seats;
    }

    /** Asks the local network who hosts a game; waits {@code millis} for answers. */
    public static List<Host> discover(int millis) {
        var found = new LinkedHashMap<String, Host>();
        try (var socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(50);
            var query = Lan.DISCOVERY_QUERY.getBytes(StandardCharsets.UTF_8);
            for (var target : targets()) {
                try {
                    socket.send(new DatagramPacket(query, query.length, target, Lan.DISCOVERY_PORT));
                } catch (IOException ignored) {
                    // an interface that cannot broadcast
                }
            }
            long end = System.nanoTime() + millis * 1_000_000L;
            var buffer = new byte[256];
            while (System.nanoTime() < end) {
                var packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                var parts = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).split(" ", 7);
                if (parts.length < 7 || !parts[0].equals(Lan.DISCOVERY_REPLY) || !parts[1].equals(String.valueOf(Lan.PROTOCOL))) continue;
                try {
                    var host = new Host(packet.getAddress().getHostAddress(), Integer.parseInt(parts[2]), Lan.clip(parts[6]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), parts[5].equals("lobby"));
                    found.putIfAbsent(host.name() + "@" + host.port(), host); // the same host may answer on loopback and the LAN
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException e) {
            System.err.println("LAN: search failed (" + e.getMessage() + ")");
        }
        return List.copyOf(found.values());
    }

    private static List<InetAddress> targets() throws IOException {
        var targets = new ArrayList<InetAddress>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        targets.add(InetAddress.getLoopbackAddress());
        for (var nic : NetworkInterface.networkInterfaces().toList()) {
            if (!nic.isUp()) continue;
            for (var address : nic.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) targets.add(address.getBroadcast());
            }
        }
        return targets;
    }

    /** IPv4 addresses others on the LAN can type to join this machine. */
    public static List<String> localAddresses() {
        var addresses = new ArrayList<String>();
        try {
            for (var nic : NetworkInterface.networkInterfaces().toList()) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (var address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) addresses.add(address.getAddress().getHostAddress());
                }
            }
        } catch (IOException e) {
            // no list then
        }
        return addresses;
    }
}
