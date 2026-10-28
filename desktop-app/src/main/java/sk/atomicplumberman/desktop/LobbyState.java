package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;
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
    private final Node gui = new Node("Lobby");
    private DesktopLauncher app;
    private BitmapText title;
    private BitmapText arenaLine;
    private BitmapText[] slots;
    private BitmapText help;
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
        var assets = app.getAssetManager();
        var camera = app.getCamera();
        gui.detachAllChildren();
        title = Ui.text(assets, camera, 56, new ColorRGBA(.35f, .9f, .85f, 1));
        title.setText("PLAYERS");
        title.setLocalTranslation((width - title.getLineWidth()) / 2, height * .92f, 0);
        gui.attachChild(title);
        arenaLine = Ui.text(assets, camera, 18, new ColorRGBA(.75f, .8f, .85f, 1));
        arenaLine.setText(Screens.arenaName(app.settings().arena) + "  ·  change it in Match settings");
        arenaLine.setLocalTranslation((width - arenaLine.getLineWidth()) / 2, height * .92f - title.getHeight(), 0);
        gui.attachChild(arenaLine);
        slots = new BitmapText[slotCount];
        // Up to five slots in one column, more in two.
        int columns = slotCount > 5 ? 2 : 1;
        int rows = (slotCount + columns - 1) / columns;
        float top = height * .92f - title.getHeight() - arenaLine.getHeight() - 24 * Ui.scale(camera);
        float bottom = 110 * Ui.scale(camera);
        for (int i = 0; i < slotCount; i++) {
            slots[i] = Ui.text(assets, camera, columns == 1 ? 22 : 18, ArenaScene.textColor(i));
            int column = i / rows;
            slots[i].setLocalTranslation(columns == 1 ? width * .2f : width * (.06f + .48f * column),
                    top - (top - bottom) * (i % rows) / rows, 0);
            gui.attachChild(slots[i]);
        }
        help = Ui.text(assets, camera, 18, new ColorRGBA(.75f, .8f, .85f, 1));
        help.setText("Bomb button: join, then ready     Action: not ready / leave     Esc: back\n"
                + "The match starts when at least two players are ready.");
        help.setLocalTranslation((width - help.getLineWidth()) / 2, 40 * Ui.scale(camera) + help.getHeight(), 0);
        gui.attachChild(help);
    }

    private void refresh() {
        var input = app.input();
        for (int i = 0; i < slotCount; i++) {
            if (i < devices.size()) {
                int device = devices.get(i);
                slots[i].setText(String.format("P%d   %s%s%n      %s", i + 1, GameInput.deviceName(device),
                        ready.get(i) ? "   READY" : "   press bomb when ready", input.controls(device)));
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
