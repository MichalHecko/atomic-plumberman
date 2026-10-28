package sk.atomicplumberman.network;

import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.Replay;
import java.util.List;

/**
 * LAN protocol (docs/protocol.md): the host simulates and sends every tick's inputs to all clients over
 * TCP; clients run the same deterministic simulation and check the host's state hash. Messages are
 * fixed binary records, never Java serialization.
 */
public final class Lan {
    public static final int PROTOCOL = 1;
    public static final int PORT = 47800;
    public static final int DISCOVERY_PORT = 47801;
    static final String DISCOVERY_QUERY = "atomic-plumberman?";
    static final String DISCOVERY_REPLY = "atomic-plumberman";
    static final int MAX_TEXT = 40;

    private Lan() { }

    /** One input frame in a byte: direction in bits 0–2, then bomb, held and action. */
    public static int pack(PlayerInputFrame f) {
        return f.direction().ordinal() | (f.bomb() ? 8 : 0) | (f.bombHeld() ? 16 : 0) | (f.action() ? 32 : 0);
    }

    public static PlayerInputFrame unpack(int packed) {
        if ((packed & ~63) != 0 || (packed & 7) >= Direction.values().length) throw new IllegalArgumentException("bad input " + packed);
        return new PlayerInputFrame(Direction.values()[packed & 7], (packed & 8) != 0, (packed & 16) != 0, (packed & 32) != 0);
    }

    static String clip(String text) {
        String clean = text.replaceAll("\\p{Cntrl}", "").strip();
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
    }

    /** A lobby place: which connection owns it and which of its local devices plays it. */
    public record Seat(int client, int local, String label, boolean ready) { }

    /** What the host sends; clients read them from {@link LanClient#poll()} in order. */
    public sealed interface Message { }
    public record Welcome(int client) implements Message { }
    public record Lobby(String arena, int capacity, List<Seat> seats) implements Message { }
    /** Arena, starts, teams, rules and match seed travel as a replay header, so clients need no matching content. */
    public record MatchStart(Replay setup, String theme, boolean teamPlay, List<Seat> players) implements Message { }
    public record Round(int number) implements Message { }
    public record Frame(long tick, List<PlayerInputFrame> inputs) implements Message { }
    public record Hash(long tick, long hash) implements Message { }
    public record Closed(String reason) implements Message { }
}
