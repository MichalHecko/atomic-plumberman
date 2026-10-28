package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;
import sk.atomicplumberman.network.Lan;
import sk.atomicplumberman.network.LanClient;

/**
 * LAN lobby, the same controls as the local one: every device on every machine joins with its bomb button,
 * presses it again when ready, Action un-readies or leaves. The host starts the match once at least two
 * joined players are all ready. The lobby itself lives on the host; this screen shows its latest state.
 */
final class NetLobbyState extends BaseAppState {
    private final Node gui = new Node("LAN lobby");
    private DesktopLauncher app;
    private Lan.Lobby lobby;
    private BitmapText title;
    private BitmapText info;
    private BitmapText[] slots = new BitmapText[0];
    private BitmapText help;
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
        var assets = app.getAssetManager();
        var camera = app.getCamera();
        gui.detachAllChildren();
        title = Ui.text(assets, camera, 56, new ColorRGBA(.35f, .9f, .85f, 1));
        title.setText("LAN LOBBY");
        title.setLocalTranslation((width - title.getLineWidth()) / 2, height * .92f, 0);
        gui.attachChild(title);
        info = Ui.text(assets, camera, 18, new ColorRGBA(.75f, .8f, .85f, 1));
        gui.attachChild(info);
        slots = new BitmapText[capacity];
        int columns = capacity > 5 ? 2 : 1;
        int rows = Math.max(1, (capacity + columns - 1) / columns);
        float top = height * .92f - title.getHeight() - 3 * info.getLineHeight() - 24 * Ui.scale(camera);
        float bottom = 110 * Ui.scale(camera);
        for (int i = 0; i < capacity; i++) {
            slots[i] = Ui.text(assets, camera, columns == 1 ? 22 : 18, ArenaScene.textColor(i));
            int column = i / rows;
            slots[i].setLocalTranslation(columns == 1 ? width * .2f : width * (.06f + .48f * column), top - (top - bottom) * (i % rows) / rows, 0);
            gui.attachChild(slots[i]);
        }
        help = Ui.text(assets, camera, 18, new ColorRGBA(.75f, .8f, .85f, 1));
        help.setText("Bomb button: join, then ready     Action: not ready / leave     Esc: "
                + (app.session().host() != null ? "end the LAN game" : "leave") + "\n"
                + "The match starts when at least two players on any machines are ready.");
        help.setLocalTranslation((width - help.getLineWidth()) / 2, 40 * Ui.scale(camera) + help.getHeight(), 0);
        gui.attachChild(help);
    }

    private void refresh(LanClient client) {
        var host = app.session().host();
        String where = host == null ? "Connected to the host."
                : "You are the host. Others choose LAN game and join you"
                + (addresses.isEmpty() ? "." : ", or start with --join=" + String.join(" / ", addresses)
                + (host.port() == Lan.PORT ? "" : ":" + host.port()));
        info.setText(lobby == null ? "Connecting…" : lobby.arena() + "\n" + where);
        info.setLocalTranslation((width - info.getLineWidth()) / 2, height * .92f - title.getHeight(), 0);
        for (int i = 0; i < slots.length; i++) {
            if (lobby != null && i < lobby.seats().size()) {
                var s = lobby.seats().get(i);
                boolean mine = s.client() == client.id();
                slots[i].setText(String.format("P%d   %s%s%s", i + 1, s.label(), mine ? "  (here)" : "",
                        s.ready() ? "   READY" : mine ? "   press bomb when ready" : "   not ready"));
                slots[i].setAlpha(1);
            } else {
                slots[i].setText(String.format("P%d   free — press a bomb button to join", i + 1));
                slots[i].setAlpha(.45f);
            }
        }
    }

    @Override protected void onEnable() {
        app.getGuiNode().attachChild(gui);
        app.input().clearEdges();
        width = 0;
    }

    @Override protected void onDisable() { gui.removeFromParent(); }
    @Override protected void cleanup(Application application) { }
}
