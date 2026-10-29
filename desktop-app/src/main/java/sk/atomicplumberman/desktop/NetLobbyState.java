package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import sk.atomicplumberman.network.Lan;
import sk.atomicplumberman.network.LanClient;

/**
 * LAN lobby, the same controls as the local one: every device on every machine joins with its bomb button,
 * presses it again when ready, Action un-readies or leaves. The host starts the match once at least two
 * joined players are all ready. The lobby itself lives on the host; this screen shows its latest state.
 */
final class NetLobbyState extends BaseAppState {
    private final Screens.LobbyView view = new Screens.LobbyView();
    private DesktopLauncher app;
    private Lan.Lobby lobby;
    private int width;
    private int height;
    private int shownCapacity = -1;
    private java.util.List<String> addresses = java.util.List.of();

    NetLobbyState() { this(null); }

    NetLobbyState(Lan.Lobby lobby) { this.lobby = lobby; }

    @Override protected void initialize(Application application) {
        app = (DesktopLauncher) application;
        if (app.session().host() != null) addresses = LanClient.localAddresses();
    }

    @Override public void update(float tpf) {
        var client = app.session().client();
        for (var m = client.poll(); m != null; m = client.poll()) {
            switch (m) {
                case Lan.Lobby next -> lobby = next;
                case Lan.MatchStart start -> {
                    open(new MatchState(start, client.id()));
                    return;
                }
                case Lan.Closed closed -> {
                    app.endSession();
                    open(new Screens.Notice("LAN GAME", closed.reason()));
                    return;
                }
                default -> { } // frames and hashes of a match this screen interrupted
            }
        }
        var input = app.input();
        boolean acted = false;
        for (int device : input.devices()) {
            var seat = seat(client, device);
            if (input.consumeBomb(device) && lobby != null) {
                if (seat == null) client.join(device, GameInput.deviceName(device));
                else client.ready(device, !seat.ready());
            }
            if (input.consumeAction(device) && seat != null) {
                acted = true;
                if (seat.ready()) client.ready(device, false);
                else client.leave(device);
            }
            if (seat != null && !input.connected(device)) client.leave(device);
        }
        input.consume(GameInput.Menu.CONFIRM);
        if (input.consume(GameInput.Menu.BACK) && !acted) {
            app.endSession();
            open(new Screens.Main());
            return;
        }
        int capacity = lobby == null ? 0 : lobby.capacity();
        if (width != app.getCamera().getWidth() || height != app.getCamera().getHeight() || capacity != shownCapacity) layout(capacity);
        refresh(client);
    }

    private Lan.Seat seat(LanClient client, int device) {
        if (lobby == null) return null;
        return lobby.seats().stream().filter(s -> s.client() == client.id() && s.local() == device).findFirst().orElse(null);
    }

    private void open(BaseAppState next) {
        getStateManager().detach(this);
        getStateManager().attach(next);
    }

    private void layout(int capacity) {
        width = app.getCamera().getWidth();
        height = app.getCamera().getHeight();
        shownCapacity = capacity;
        view.layout(app.getAssetManager(), app.getCamera(), "LAN LOBBY", capacity,
                "Bomb button: join, then ready     Action: not ready / leave     Esc: "
                + (app.session().host() != null ? "end the LAN game" : "leave") + "\n"
                + "The match starts when at least two players on any machines are ready.");
    }

    private void refresh(LanClient client) {
        var host = app.session().host();
        String where = host == null ? "Connected to the host."
                : "You are the host. Others choose LAN game and join you"
                + (addresses.isEmpty() ? "." : ", or start with --join=" + String.join(" / ", addresses)
                + (host.port() == Lan.PORT ? "" : ":" + host.port()));
        view.info(lobby == null ? "Connecting…" : lobby.arena() + "\n" + where);
        for (int i = 0; i < view.slots(); i++) {
            if (lobby != null && i < lobby.seats().size()) {
                var s = lobby.seats().get(i);
                boolean mine = s.client() == client.id();
                view.slot(i, String.format("P%d   %s%s%s", i + 1, s.label(), mine ? "  (here)" : "",
                        s.ready() ? "   READY" : mine ? "   press bomb when ready" : "   not ready"), true, s.ready());
            } else {
                view.slot(i, String.format("P%d   free — press a bomb button to join", i + 1), false, false);
            }
        }
        view.update();
    }

    @Override protected void onEnable() {
        app.getGuiNode().attachChild(view.node);
        app.input().clearEdges();
        width = 0;
    }

    @Override protected void onDisable() { view.node.removeFromParent(); }
    @Override protected void cleanup(Application application) { }
}
