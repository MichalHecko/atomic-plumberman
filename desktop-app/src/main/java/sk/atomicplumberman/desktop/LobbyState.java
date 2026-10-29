package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import java.util.ArrayList;
import java.util.List;

/**
 * As many players as the chosen arena has starts join with their own device: bomb joins, bomb again
 * toggles ready, action un-readies or leaves. The match starts once at least two joined players are all ready.
 */
final class LobbyState extends BaseAppState {
    private int slotCount;
    private final List<Integer> devices = new ArrayList<>();
    private final List<Boolean> ready = new ArrayList<>();
    private final Screens.LobbyView view = new Screens.LobbyView();
    private DesktopLauncher app;
    private int width;
    private int height;

    /** {@code previous} keeps the devices of the last match, not ready. */
    LobbyState(int[] previous) {
        for (int device : previous) {
            devices.add(device);
            ready.add(false);
        }
    }

    @Override protected void initialize(Application application) {
        app = (DesktopLauncher) application;
        slotCount = MatchState.maxPlayers(app.settings().arena);
        while (devices.size() > slotCount) leave(devices.size() - 1);
    }

    @Override public void update(float tpf) {
        var input = app.input();
        if (width != app.getCamera().getWidth() || height != app.getCamera().getHeight()) layout();
        boolean acted = false;
        for (int device : input.devices()) {
            int slot = devices.indexOf(device);
            if (input.consumeBomb(device)) {
                if (slot >= 0) ready.set(slot, !ready.get(slot));
                else if (devices.size() < slotCount) {
                    devices.add(device);
                    ready.add(false);
                }
            }
            if (input.consumeAction(device) && slot >= 0) {
                acted = true;
                if (ready.get(slot)) ready.set(slot, false);
                else leave(slot);
            }
        }
        for (int slot = devices.size() - 1; slot >= 0; slot--) {
            if (!input.connected(devices.get(slot))) leave(slot);
        }
        input.consume(GameInput.Menu.CONFIRM);
        if (input.consume(GameInput.Menu.BACK) && !acted) {
            open(new Screens.Main());
            return;
        }
        if (devices.size() >= 2 && !ready.contains(false)) {
            open(new MatchState(devices.stream().mapToInt(Integer::intValue).toArray(), System.nanoTime(), null));
            return;
        }
        refresh();
    }

    private void leave(int slot) {
        devices.remove(slot);
        ready.remove(slot);
    }

    private void open(BaseAppState next) {
        getStateManager().detach(this);
        getStateManager().attach(next);
    }

    private void layout() {
        width = app.getCamera().getWidth();
        height = app.getCamera().getHeight();
        view.layout(app.getAssetManager(), app.getCamera(), "PLAYERS", slotCount,
                "Bomb button: join, then ready     Action: not ready / leave     Esc: back\n"
                + "The match starts when at least two players are ready.");
        view.info(Screens.arenaName(app.settings().arena) + "  ·  change it in Match settings");
    }

    private void refresh() {
        var input = app.input();
        for (int i = 0; i < slotCount; i++) {
            if (i < devices.size()) {
                int device = devices.get(i);
                view.slot(i, String.format("P%d   %s%s%n%s", i + 1, GameInput.deviceName(device),
                        ready.get(i) ? "   READY" : "   press bomb when ready", input.controls(device)), true, ready.get(i));
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
