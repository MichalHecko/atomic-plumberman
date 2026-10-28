package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;
import sk.atomicplumberman.network.Lan;
import sk.atomicplumberman.network.LanClient;
import sk.atomicplumberman.network.LanServer;
import java.io.IOException;
import java.util.List;

/** Menu screens: a title, optional body text and a {@link MenuList}; they rebuild on window resize. */
final class Screens {
    private Screens() { }

    abstract static class Screen extends BaseAppState {
        final Node gui = new Node("Screen");
        DesktopLauncher app;
        MenuList menu;
        private int width;
        private int height;

        abstract String title();
        String body() { return ""; }
        float bodySize() { return 19; }
        abstract List<MenuList.Item> items();
        /** Back / Esc; null means back does nothing. */
        abstract Runnable back();

        void open(BaseAppState next) {
            getStateManager().detach(this);
            getStateManager().attach(next);
        }

        @Override protected void initialize(Application application) {
            app = (DesktopLauncher) application;
            menu = new MenuList(items());
        }

        @Override public void update(float tpf) {
            var camera = app.getCamera();
            if (width != camera.getWidth() || height != camera.getHeight()) {
                width = camera.getWidth();
                height = camera.getHeight();
                layout();
            }
            var back = back();
            if (back != null && app.input().consume(GameInput.Menu.BACK)) {
                back.run();
                return;
            }
            menu.update(app.input(), app.mixer());
        }

        private void layout() {
            gui.detachAllChildren();
            var camera = app.getCamera();
            var title = Ui.text(app.getAssetManager(), camera, 56, new ColorRGBA(.35f, .9f, .85f, 1));
            title.setText(title());
            title.setLocalTranslation((width - title.getLineWidth()) / 2, height * .86f, 0);
            gui.attachChild(title);
            float y = height * .86f - title.getHeight() - 24 * Ui.scale(camera);
            if (!body().isEmpty()) {
                BitmapText body = Ui.text(app.getAssetManager(), camera, bodySize(), new ColorRGBA(.85f, .88f, .92f, 1));
                body.setText(body());
                body.setLocalTranslation((width - body.getLineWidth()) / 2, y, 0);
                gui.attachChild(body);
                y -= body.getHeight() + 28 * Ui.scale(camera);
            }
            menu.layout(app.getAssetManager(), camera, width / 2f, y);
            gui.attachChild(menu.node);
        }

        @Override protected void onEnable() {
            app.getGuiNode().attachChild(gui);
            app.input().clearEdges();
            width = 0;
        }

        @Override protected void onDisable() { gui.removeFromParent(); }
        @Override protected void cleanup(Application application) { }
    }

    static final class Main extends Screen {
        @Override String title() { return "ATOMIC PLUMBERMAN"; }
        @Override String body() { return "Arena battle for 2–10 players, on one computer or over LAN\nversion " + DesktopLauncher.VERSION; }

        @Override List<MenuList.Item> items() {
            return List.of(
                    MenuList.Item.action("Play", () -> open(new LobbyState(new int[0]))),
                    MenuList.Item.action("LAN game", () -> open(new LanMenu())),
                    MenuList.Item.action("Match settings", () -> open(new SettingsScreen(false))),
                    MenuList.Item.action("Options", () -> open(new SettingsScreen(true))),
                    MenuList.Item.action("Controls", () -> open(new Controls())),
                    MenuList.Item.action("Help", () -> open(new Help())),
                    MenuList.Item.action("Quit", () -> app.stop()));
        }

        @Override Runnable back() { return null; } // quitting is the explicit menu item
    }

    /** Match settings (arena, theme, rules) or options (audio, effects); both save on leaving. */
    static final class SettingsScreen extends Screen {
        private final boolean options;

        SettingsScreen(boolean options) { this.options = options; }

        @Override String title() { return options ? "OPTIONS" : "MATCH SETTINGS"; }

        @Override List<MenuList.Item> items() {
            var s = app.settings();
            var items = new java.util.ArrayList<MenuList.Item>();
            if (options) {
                items.add(MenuList.Item.value(() -> "Master volume:  " + s.volume + " %", d -> {
                    s.volume = Math.clamp(s.volume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value(() -> "Music volume:  " + s.musicVolume + " %", d -> {
                    s.musicVolume = Math.clamp(s.musicVolume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value(() -> "Effects volume:  " + s.effectsVolume + " %", d -> {
                    s.effectsVolume = Math.clamp(s.effectsVolume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value(() -> "Camera shake:  " + (s.cameraShake ? "on" : "off"), d -> s.cameraShake = !s.cameraShake));
                items.add(MenuList.Item.value(() -> "Flickering flames:  " + (s.flicker ? "on" : "off"), d -> s.flicker = !s.flicker));
            } else {
                items.add(MenuList.Item.value(() -> "Arena:  " + arenaName(s.arena),
                        d -> s.arena = Settings.ARENAS.get(Math.floorMod(Settings.ARENAS.indexOf(s.arena) + d, Settings.ARENAS.size()))));
                items.add(MenuList.Item.value(() -> "Theme:  " + themeName(s.theme),
                        d -> s.theme = Settings.THEMES.get(Math.floorMod(Settings.THEMES.indexOf(s.theme) + d, Settings.THEMES.size()))));
                items.add(MenuList.Item.value(() -> "Match decided by:  " + (s.killsMode ? "kills" : "round wins"), d -> s.killsMode = !s.killsMode));
                items.add(MenuList.Item.value(() -> s.killsMode ? "Kills to take the match:  " + s.killsToWin
                        : "Wins to take the match:  " + s.winsToWin, d -> {
                    if (s.killsMode) s.killsToWin = Math.clamp(s.killsToWin + d, 1, 20);
                    else s.winsToWin = Math.clamp(s.winsToWin + d, 1, 9);
                }));
                items.add(MenuList.Item.value(() -> "Teams:  " + (s.teams ? "odd slots vs even slots" : "off (Wall Duel always)"),
                        d -> s.teams = !s.teams));
                items.add(MenuList.Item.value(() -> "Flames hurt teammates:  " + (s.teamDamage ? "yes" : "no"), d -> s.teamDamage = !s.teamDamage));
                items.add(MenuList.Item.value(() -> String.format("Round time:  %d:%02d", s.roundSeconds / 60, s.roundSeconds % 60),
                        d -> s.roundSeconds = Math.clamp(s.roundSeconds + 30 * d, 60, 300)));
                items.add(MenuList.Item.value(() -> "Sudden death in the last 30 s:  " + (s.suddenDeath ? "on" : "off"),
                        d -> s.suddenDeath = !s.suddenDeath));
            }
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override Runnable back() { return () -> saveAndOpen(this, new Main()); }
    }

    /** Saves the settings, or says why it could not, then goes on to {@code next}. */
    static void saveAndOpen(Screen screen, BaseAppState next) {
        try {
            screen.app.settings().save();
            screen.open(next);
        } catch (IOException exception) {
            System.err.println("SETTINGS: cannot save (" + exception.getMessage() + ")");
            screen.open(new Notice("SETTINGS", "Settings were not saved: " + exception.getMessage()
                    + "\n" + screen.app.settings().directory + "\nThey stay in effect until the game is closed."));
        }
    }

    /** Keyboard profiles: choose an action, then press its new key; a key bound elsewhere swaps places. */
    static final class Controls extends Screen {
        private static final List<Integer> RESERVED = List.of(KeyInput.KEY_ESCAPE, KeyInput.KEY_P, KeyInput.KEY_F3);
        private int waiting = -1;
        private int refused = -1;
        private String note = "";

        @Override String title() { return "CONTROLS"; }
        @Override float bodySize() { return 16; }

        @Override String body() {
            return "Choose an action, then press its new key. Esc cancels; Esc, P and F3 are reserved.\n"
                    + "Gamepads: stick or D-pad, A bomb, B action, Start pause.";
        }

        @Override List<MenuList.Item> items() {
            var items = new java.util.ArrayList<MenuList.Item>();
            for (int profile = 0; profile < 2; profile++) {
                for (int action = 0; action < Settings.ACTIONS.length; action++) {
                    int index = profile * Settings.ACTIONS.length + action, p = profile, a = action;
                    items.add(new MenuList.Item(() -> GameInput.deviceName(p) + "  " + Settings.ACTIONS[a] + ":  "
                            + (waiting == index ? "press a key…" : Settings.keyName(app.settings().keys[p][a]))
                            + (waiting < 0 && index == refused ? note : ""), () -> {
                        app.input().consumeKey();
                        waiting = index;
                    }, null));
                }
            }
            items.add(MenuList.Item.action("Reset to defaults", () -> {
                for (int p = 0; p < 2; p++) System.arraycopy(Settings.DEFAULT_KEYS[p], 0, app.settings().keys[p], 0, Settings.ACTIONS.length);
            }));
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override public void update(float tpf) {
            if (waiting < 0) {
                super.update(tpf);
                return;
            }
            int key = app.input().consumeKey();
            if (key < 0) return;
            if (key != KeyInput.KEY_ESCAPE) {
                refused = RESERVED.contains(key) ? waiting : -1;
                note = "   (" + Settings.keyName(key) + " is reserved)";
                if (refused < 0) app.settings().bind(waiting / Settings.ACTIONS.length, waiting % Settings.ACTIONS.length, key);
            }
            app.input().clearEdges(); // the key must not also move the menu or go back
            waiting = -1;
            menu.update(app.input(), app.mixer());
        }

        @Override Runnable back() { return () -> saveAndOpen(this, new Main()); }
    }

    /**
     * Host a LAN game in the arena and with the match settings chosen here, or join a game found on
     * this network. Searching runs in the background while the screen is open.
     */
    static final class LanMenu extends Screen {
        private static final int LISTED = 4;
        private volatile List<LanClient.Host> hosts = List.of();
        private volatile boolean searching;

        @Override String title() { return "LAN GAME"; }

        @Override String body() {
            return "Host: the other computers on your network join your game; Match settings apply.\n"
                    + "Join: games found on this network. Without one, start the game with --join=ADDRESS.";
        }

        @Override List<MenuList.Item> items() {
            var s = app.settings();
            var items = new java.util.ArrayList<MenuList.Item>();
            items.add(MenuList.Item.value(() -> "Arena:  " + arenaName(s.arena),
                    d -> s.arena = Settings.ARENAS.get(Math.floorMod(Settings.ARENAS.indexOf(s.arena) + d, Settings.ARENAS.size()))));
            items.add(MenuList.Item.action("Host a game", this::host));
            for (int i = 0; i < LISTED; i++) {
                int index = i;
                items.add(new MenuList.Item(() -> {
                    var found = hosts;
                    if (index >= found.size()) return index == 0 ? "Searching for games on this network…" : "";
                    var h = found.get(index);
                    return "Join " + h.name() + "  ·  " + h.seats() + "/" + h.capacity() + (h.inLobby() ? "" : "  ·  playing");
                }, () -> {
                    var found = hosts;
                    if (index < found.size()) join(found.get(index).address(), found.get(index).port());
                }, null));
            }
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override Runnable back() { return () -> open(new Main()); }

        private void host() {
            var s = app.settings();
            var arena = MatchState.loadArena(s.arena);
            try {
                var setup = new LanServer.Setup(arena, MatchState.rules(arena, s), MatchState.themeId(arena, s), s.teams);
                var server = LanServer.start(Lan.PORT, playerName(), setup, 3_000_000_000L);
                try {
                    app.startSession(server, LanClient.connect("127.0.0.1", server.port(), playerName()));
                } catch (IOException e) {
                    server.close();
                    throw e;
                }
                open(new NetLobbyState());
            } catch (IOException e) {
                open(new Notice("LAN GAME", "Cannot host on port " + Lan.PORT + ": " + e.getMessage()
                        + "\nIs another game already hosting on this computer?"));
            }
        }

        private void join(String address, int port) {
            try {
                app.startSession(null, LanClient.connect(address, port, playerName()));
                open(new NetLobbyState());
            } catch (IOException e) {
                open(new Notice("LAN GAME", "Cannot connect to " + address + ": " + e.getMessage()));
            }
        }

        @Override protected void onEnable() {
            super.onEnable();
            searching = true;
            var thread = new Thread(() -> {
                while (searching) hosts = LanClient.discover(800);
            }, "lan-search");
            thread.setDaemon(true);
            thread.start();
        }

        @Override protected void onDisable() {
            searching = false;
            super.onDisable();
        }
    }

    static String playerName() {
        return System.getProperty("user.name", "player");
    }

    /** A message with a single OK that returns to the main menu. */
    static final class Notice extends Screen {
        private final String title;
        private final String body;

        Notice(String title, String body) {
            this.title = title;
            this.body = body;
        }

        @Override String title() { return title; }
        @Override String body() { return body; }
        @Override List<MenuList.Item> items() { return List.of(MenuList.Item.action("OK", back())); }
        @Override Runnable back() { return () -> open(new Main()); }
    }

    static String arenaName(String id) {
        var arena = MatchState.loadArena(id);
        return arena.name() + "  ·  up to " + arena.starts().size();
    }

    static String themeName(String id) {
        return id.equals("map") ? "the arena's own" : MatchState.loadTheme(id).name();
    }

    static final class Help extends Screen {
        @Override String title() { return "HELP"; }
        @Override float bodySize() { return 16; }

        @Override String body() {
            var input = app.input();
            return """
                    Join in the lobby with your bomb button, press it again when ready.
                    %s: %s
                    %s: %s
                    Gamepads: stick or D-pad, A bomb, B action, Start pause.

                    Blow up crates, collect pickups and be the last one (or team) standing.
                    bomb: more bombs   flame: longer flames   arrows: faster   red flame: flames across the arena
                    shoe: kick bombs by walking into them   boxing glove: punch the bomb ahead over walls
                    white glove: press bomb on your bomb to lift it, hold to carry, release to throw
                    two bombs: press bomb again at once for a row   detonator: bombs wait for Action
                    spring: bouncing bombs   ?: random   skull: disease (D+ worse), cured by any good pickup
                    Action: Trigger first, else Punch, else stop your last kicked bomb.
                    Pairs exclude each other (white glove/two bombs, boxing glove/detonator, spring/detonator): the old is dropped.
                    Belts carry players and bombs; teleports (lettered pairs) move you on entry.
                    Each player has a colour, as in the top bar; in team play a coloured shadow marks the team.
                    In the last 30 seconds the arena closes in from the border (if enabled).

                    Esc / Start: pause     F3: diagnostics"""
                    .formatted(GameInput.deviceName(GameInput.KEYBOARD_A), input.controls(GameInput.KEYBOARD_A),
                            GameInput.deviceName(GameInput.KEYBOARD_B), input.controls(GameInput.KEYBOARD_B));
        }

        @Override List<MenuList.Item> items() { return List.of(MenuList.Item.action("Back", back())); }
        @Override Runnable back() { return () -> open(new Main()); }
    }
}
