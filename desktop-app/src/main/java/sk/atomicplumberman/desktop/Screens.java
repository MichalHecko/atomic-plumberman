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

    /**
     * Behind every menu: dim bombs in the player colours drift up through rising embers; on the main menu the
     * ten robots parade along the bottom. Driven by the clock, so it flows on across screens.
     */
    static final class Backdrop {
        private static final int BOMBS = 14;
        private static final int EMBERS = 26;
        final Node node = new Node("Backdrop");
        private final List<com.jme3.ui.Picture> bombs = new java.util.ArrayList<>();
        private final List<com.jme3.ui.Picture> embers = new java.util.ArrayList<>();
        private final List<com.jme3.ui.Picture> robots = new java.util.ArrayList<>();
        private final float width;
        private final float height;
        private final float scale;

        private final float paradeY;

        /** {@code paradeY} is the robots' baseline, below 0 for no parade. */
        Backdrop(com.jme3.asset.AssetManager assets, com.jme3.renderer.Camera camera, float paradeY) {
            this.paradeY = paradeY;
            boolean parade = paradeY >= 0;
            width = camera.getWidth();
            height = camera.getHeight();
            scale = Ui.scale(camera);
            var texture = assets.loadTexture("Textures/Bombman/bomb1.png");
            for (int i = 0; i < BOMBS; i++) {
                var bomb = new com.jme3.ui.Picture("Bomb");
                bomb.setTexture(assets, (com.jme3.texture.Texture2D) texture, true);
                float size = (40 + 70 * random(i, 1)) * scale;
                bomb.setWidth(size * 50 / 43);
                bomb.setHeight(size);
                var c = ArenaScene.PLAYER_COLORS[i % ArenaScene.PLAYER_COLORS.length];
                Ui.tint(bomb, new ColorRGBA(.35f + c.r * .4f, .35f + c.g * .4f, .35f + c.b * .4f, .13f + .1f * random(i, 2)));
                bombs.add(bomb);
                node.attachChild(bomb);
            }
            var dot = Ui.dot(Math.max(4, Math.round(7 * scale)));
            for (int i = 0; i < EMBERS; i++) {
                var ember = Ui.picture(assets, dot, 0, 0);
                embers.add(ember);
                node.attachChild(ember);
            }
            for (int slot = 0; parade && slot < ArenaScene.PLAYER_COLORS.length; slot++) {
                var robot = new com.jme3.ui.Picture("Robot");
                robot.setTexture(assets, ArenaScene.robot("player_right", slot), true);
                robot.setHeight(70 * scale);
                robot.setWidth(70 * scale * 60 / 94);
                robots.add(robot);
                node.attachChild(robot);
            }
            update();
        }

        /** Stable pseudo-random 0..1 per element and purpose. */
        private static float random(int i, int salt) {
            long h = (i * 0x9E3779B97F4A7C15L + salt) * 0xBF58476D1CE4E5B9L;
            return ((h ^ h >>> 31) >>> 40) / (float) (1 << 24);
        }

        void update() {
            float time = (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
            for (int i = 0; i < bombs.size(); i++) {
                var b = bombs.get(i);
                float travel = height + 2 * b.getHeight();
                float y = (time * (14 + 22 * random(i, 3)) * scale + random(i, 4) * travel) % travel - b.getHeight();
                float x = random(i, 5) * width + (float) Math.sin(time * .4f + i) * 30 * scale;
                b.setLocalTranslation(x, y, -2);
                b.setLocalRotation(new com.jme3.math.Quaternion().fromAngleAxis((float) Math.sin(time * .3f + i * 2) * .4f,
                        com.jme3.math.Vector3f.UNIT_Z));
            }
            for (int i = 0; i < embers.size(); i++) {
                var e = embers.get(i);
                float y = (time * (40 + 60 * random(i, 6)) * scale + random(i, 7) * height) % (height * .8f);
                float x = random(i, 8) * width + (float) Math.sin(time * 1.3f + i * 3) * 18 * scale;
                e.setLocalTranslation(x, y, -1);
                float fade = 1 - y / (height * .8f);
                Ui.tint(e, new ColorRGBA(1, .55f + .35f * random(i, 9), .2f, fade * (.45f + .3f * (float) Math.sin(time * 9 + i))));
            }
            String[] walk = {"player_right_walk1", "player_right", "player_right_walk3", "player_right"};
            float gap = 120 * scale;
            for (int i = 0; i < robots.size(); i++) {
                var r = robots.get(i);
                float loop = width + gap;
                float x = (time * 80 * scale + i * loop / robots.size()) % loop - gap;
                r.setTexture(null, ArenaScene.robot(walk[(int) (time * 7 + i) % 4], i), true);
                r.setLocalTranslation(x, paradeY + Math.abs((float) Math.sin(time * 11 + i)) * 3 * scale, -1);
            }
        }
    }

    /**
     * The look of both lobbies: backdrop, banner title, an info line, one card per slot with the slot's robot
     * (hopping when ready, faded while free) and the help text at the bottom.
     */
    static final class LobbyView {
        final Node node = new Node("Lobby");
        private Backdrop backdrop;
        private BitmapText info;
        private float infoTop;
        private float width;
        private BitmapText[] texts = new BitmapText[0];
        private com.jme3.ui.Picture[] cards;
        private com.jme3.ui.Picture[] robots;
        private float[] robotY;
        private boolean[] ready;
        private float scale;

        void layout(com.jme3.asset.AssetManager assets, com.jme3.renderer.Camera camera, String title, int slots, String help) {
            node.detachAllChildren();
            width = camera.getWidth();
            float height = camera.getHeight();
            scale = Ui.scale(camera);
            float s = scale;
            backdrop = new Backdrop(assets, camera, -1);
            node.attachChild(backdrop.node);
            var banner = Ui.banner(title, Math.round(54 * s), Ui.awt(185, 255, 240, 255), Ui.awt(25, 165, 170, 255));
            float top = height * .95f - banner.getImage().getHeight();
            node.attachChild(Ui.picture(assets, banner, (width - banner.getImage().getWidth()) / 2, top));
            info = Ui.text(assets, camera, 18, new ColorRGBA(.8f, .85f, .9f, 1));
            infoTop = top - 6 * s;
            node.attachChild(info);
            var helpText = Ui.text(assets, camera, 16, new ColorRGBA(.6f, .65f, .75f, 1));
            helpText.setText(help);
            helpText.setLocalTranslation((width - helpText.getLineWidth()) / 2, 14 * s + helpText.getHeight(), 0);
            node.attachChild(helpText);
            int columns = slots > 5 ? 2 : 1;
            int rows = Math.max(1, (slots + columns - 1) / columns);
            float areaTop = infoTop - 2.4f * info.getLineHeight() - 12 * s;
            float areaBottom = 28 * s + helpText.getHeight();
            float pitch = (areaTop - areaBottom) / rows;
            float cardHeight = Math.min(pitch - 8 * s, 66 * s);
            float cardWidth = columns == 1 ? Math.min(width * .62f, 820 * s) : Math.min(width * .45f, 600 * s);
            texts = new BitmapText[slots];
            cards = new com.jme3.ui.Picture[slots];
            robots = new com.jme3.ui.Picture[slots];
            robotY = new float[slots];
            ready = new boolean[slots];
            for (int i = 0; i < slots; i++) {
                int column = i / rows;
                float x = columns == 1 ? (width - cardWidth) / 2 : width / 2 + (column == 0 ? -cardWidth - 10 * s : 10 * s);
                float y = areaTop - (i % rows) * pitch - cardHeight;
                var c = ArenaScene.PLAYER_COLORS[i % ArenaScene.PLAYER_COLORS.length];
                cards[i] = Ui.picture(assets, Ui.panel(Math.round(cardWidth), Math.round(cardHeight), Math.round(12 * s), Ui.awt(20, 24, 42, 225),
                        Ui.awt(Math.round(55 + c.r * 200), Math.round(55 + c.g * 200), Math.round(55 + c.b * 200), 235), 2.5f * s), x, y);
                node.attachChild(cards[i]);
                robots[i] = new com.jme3.ui.Picture("Robot");
                robots[i].setTexture(assets, ArenaScene.robot("player_down", i), true);
                robots[i].setHeight(cardHeight * 1.05f);
                robots[i].setWidth(cardHeight * 1.05f * 60 / 94);
                robotY[i] = y + 3 * s;
                robots[i].setLocalTranslation(x + 12 * s, robotY[i], .5f);
                node.attachChild(robots[i]);
                texts[i] = Ui.text(assets, camera, columns == 1 ? 20 : 17, ArenaScene.textColor(i));
                texts[i].setUserData("box", new com.jme3.math.Vector3f(x + 24 * s + robots[i].getWidth(), y, cardHeight));
                node.attachChild(texts[i]);
            }
        }

        void info(String text) {
            info.setText(text);
            info.setLocalTranslation((width - info.getLineWidth()) / 2, infoTop, 0);
        }

        /** A slot's text; a free slot fades, a ready one turns green and its robot hops. */
        void slot(int i, String text, boolean joined, boolean isReady) {
            texts[i].setText(text);
            com.jme3.math.Vector3f box = texts[i].getUserData("box");
            texts[i].setLocalTranslation(box.x, box.y + (box.z + texts[i].getHeight()) / 2, 1);
            texts[i].setAlpha(joined ? 1 : .45f);
            Ui.tint(cards[i], !joined ? new ColorRGBA(1, 1, 1, .4f) : isReady ? new ColorRGBA(.75f, 1.6f, .85f, 1) : ColorRGBA.White);
            Ui.tint(robots[i], joined ? ColorRGBA.White : new ColorRGBA(.5f, .5f, .55f, .45f));
            ready[i] = isReady;
        }

        int slots() { return texts.length; }

        void update() {
            backdrop.update();
            float time = System.nanoTime() / 1e9f;
            for (int i = 0; i < robots.length; i++) {
                float hop = ready[i] ? Math.abs((float) Math.sin(time * 7 + i)) * 6 * scale : 0;
                robots[i].setLocalTranslation(robots[i].getLocalTranslation().x, robotY[i] + hop, .5f);
            }
        }
    }

    abstract static class Screen extends BaseAppState {
        final Node gui = new Node("Screen");
        DesktopLauncher app;
        MenuList menu;
        private Backdrop backdrop;
        private com.jme3.ui.Picture logo;
        private BitmapText hint;
        private String shownHint;
        private int width;
        private int height;

        abstract String title();
        String body() { return ""; }
        float bodySize() { return 19; }
        abstract List<MenuList.Item> items();
        /** Back / Esc; null means back does nothing. */
        abstract Runnable back();
        boolean main() { return false; }

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
            backdrop.update();
            if (!menu.hint().equals(shownHint)) {
                shownHint = menu.hint();
                hint.setText(shownHint);
                hint.setLocalTranslation((width - hint.getLineWidth()) / 2, hint.getLocalTranslation().y, 0);
            }
            if (main()) { // the logo breathes; a picture's size is its scale
                float pulse = 1 + .02f * (float) Math.sin(System.nanoTime() / 1e9 * 2.2);
                var image = logo.getMaterial().getTextureParam("Texture").getTextureValue().getImage();
                logo.setWidth(image.getWidth() * pulse);
                logo.setHeight(image.getHeight() * pulse);
                logo.setPosition((width - logo.getWidth()) / 2, logo.<Float>getUserData("y") - (logo.getHeight() - image.getHeight()) / 2);
            }
        }

        private void layout() {
            gui.detachAllChildren();
            var camera = app.getCamera();
            var assets = app.getAssetManager();
            float s = Ui.scale(camera);
            var footer = Ui.text(assets, camera, 15, new ColorRGBA(.55f, .6f, .7f, 1));
            hint = Ui.text(assets, camera, 17, new ColorRGBA(1, .85f, .55f, 1));
            float hintTop = 22 * s + footer.getLineHeight() + hint.getLineHeight();
            backdrop = new Backdrop(assets, camera, main() ? hintTop + 8 * s : -1);
            gui.attachChild(backdrop.node);
            var banner = main()
                    ? Ui.banner(title(), Math.round(84 * s), Ui.awt(255, 240, 150, 255), Ui.awt(240, 105, 20, 255))
                    : Ui.banner(title(), Math.round(54 * s), Ui.awt(185, 255, 240, 255), Ui.awt(25, 165, 170, 255));
            float top = height * (main() ? .93f : .95f);
            logo = Ui.picture(assets, banner, (width - banner.getImage().getWidth()) / 2f, top - banner.getImage().getHeight());
            logo.setUserData("y", top - banner.getImage().getHeight());
            gui.attachChild(logo);
            float y = top - banner.getImage().getHeight() - 18 * s;
            if (!body().isEmpty()) {
                BitmapText body = Ui.text(assets, camera, bodySize(), new ColorRGBA(.85f, .88f, .92f, 1));
                body.setText(body());
                body.setLocalTranslation((width - body.getLineWidth()) / 2, y, 1);
                float padX = 24 * s, padY = 12 * s;
                gui.attachChild(Ui.picture(assets, Ui.panel(Math.round(body.getLineWidth() + 2 * padX), Math.round(body.getHeight() + 2 * padY),
                        Math.round(12 * s), Ui.awt(14, 16, 32, 170), null, 0), (width - body.getLineWidth()) / 2 - padX, y - body.getHeight() - padY));
                gui.attachChild(body);
                y -= body.getHeight() + 34 * s;
            }
            footer.setText("↑ ↓  choose        ← →  change        Enter  confirm" + (back() == null ? "" : "        Esc  back"));
            footer.setLocalTranslation((width - footer.getLineWidth()) / 2, 12 * s + footer.getLineHeight(), 0);
            gui.attachChild(footer);
            hint.setLocalTranslation(0, hintTop, 0);
            shownHint = null;
            gui.attachChild(hint);
            float bottom = hintTop + (main() ? 92 : 12) * s;
            menu.layout(assets, camera, width / 2f, y, bottom);
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
        @Override String body() { return "Arena battle for 2–10 players, on one computer or over LAN   ·   version " + DesktopLauncher.VERSION; }
        @Override float bodySize() { return 16; }
        @Override boolean main() { return true; }

        @Override List<MenuList.Item> items() {
            return List.of(
                    MenuList.Item.action("Play", () -> open(new LobbyState(new int[0])), "Everyone at this computer joins with their own keys or gamepad."),
                    MenuList.Item.action("LAN game", () -> open(new LanMenu()), "Host a game or join one on your network."),
                    MenuList.Item.action("Match settings", () -> open(new SettingsScreen(false)), "Arena, theme, how a match is won, and the rules."),
                    MenuList.Item.action("Options", () -> open(new SettingsScreen(true)), "Sound and visual effects."),
                    MenuList.Item.action("Controls", () -> open(new Controls()), "Change the keys of both keyboard players."),
                    MenuList.Item.action("Help", () -> open(new Help()), "Pickups, abilities and diseases explained."),
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
                items.add(MenuList.Item.value("Master volume", () -> s.volume + " %", d -> {
                    s.volume = Math.clamp(s.volume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value("Music volume", () -> s.musicVolume + " %", d -> {
                    s.musicVolume = Math.clamp(s.musicVolume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value("Effects volume", () -> s.effectsVolume + " %", d -> {
                    s.effectsVolume = Math.clamp(s.effectsVolume + 10 * d, 0, 100);
                    app.applyVolume();
                }));
                items.add(MenuList.Item.value("Camera shake", () -> onOff(s.cameraShake), d -> s.cameraShake = !s.cameraShake,
                        "The view trembles with every explosion."));
                items.add(MenuList.Item.value("Flickering flames", () -> onOff(s.flicker), d -> s.flicker = !s.flicker,
                        "Fire alternates between two frames; turn off if flicker bothers you."));
            } else {
                items.add(MenuList.Item.value("Arena", () -> arenaName(s.arena),
                        d -> s.arena = Settings.ARENA_CHOICES.get(Math.floorMod(Settings.ARENA_CHOICES.indexOf(s.arena) + d, Settings.ARENA_CHOICES.size())),
                        "Random picks a new arena with room for everyone each match."));
                items.add(MenuList.Item.value("Theme", () -> themeName(s.theme),
                        d -> s.theme = Settings.THEMES.get(Math.floorMod(Settings.THEMES.indexOf(s.theme) + d, Settings.THEMES.size()))));
                items.add(MenuList.Item.value("Match decided by", () -> s.killsMode ? "kills" : "round wins", d -> s.killsMode = !s.killsMode));
                items.add(new MenuList.Item(() -> s.killsMode ? "Kills to take the match" : "Wins to take the match",
                        () -> String.valueOf(s.killsMode ? s.killsToWin : s.winsToWin), null, d -> {
                    if (s.killsMode) s.killsToWin = Math.clamp(s.killsToWin + d, 1, 20);
                    else s.winsToWin = Math.clamp(s.winsToWin + d, 1, 9);
                }, ""));
                items.add(MenuList.Item.value("Teams", () -> s.teams ? "odd vs even slots" : "off", d -> s.teams = !s.teams,
                        "With more than two players P1 + P3 + … play against P2 + P4 + …; Wall Duel always plays in teams."));
                items.add(MenuList.Item.value("Flames hurt teammates", () -> s.teamDamage ? "yes" : "no", d -> s.teamDamage = !s.teamDamage));
                items.add(MenuList.Item.value("Round time", () -> String.format("%d:%02d", s.roundSeconds / 60, s.roundSeconds % 60),
                        d -> s.roundSeconds = Math.clamp(s.roundSeconds + 30 * d, 60, 300)));
                items.add(MenuList.Item.value("Sudden death", () -> s.suddenDeath ? "last 30 s" : "off", d -> s.suddenDeath = !s.suddenDeath,
                        "In the last 30 seconds the arena closes in from the border."));
                items.add(MenuList.Item.action("Rules…", () -> open(new RulesScreen()), "Speed, bombs, fuse, fire, crates, kicks and more."));
            }
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override Runnable back() { return () -> saveAndOpen(this, new Main()); }
    }

    /** {@link Settings#RULES}: each steps through its choices; the hint names the default. */
    static final class RulesScreen extends Screen {
        @Override String title() { return "RULES"; }

        @Override List<MenuList.Item> items() {
            var s = app.settings();
            var items = new java.util.ArrayList<MenuList.Item>();
            for (var rule : Settings.RULES) {
                String c = rule.component();
                items.add(MenuList.Item.value(rule.label(), () -> rule.show().apply(s.rules.get(c)) + (s.rules.get(c) == rule.fallback() ? "" : "  •"),
                        d -> s.rules.put(c, rule.step(s.rules.get(c), d)), rule.hint() + "   Default: " + rule.show().apply(rule.fallback()) + "."));
            }
            items.add(MenuList.Item.action("Reset to defaults", s::resetRules, "Changed values are marked with •."));
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override Runnable back() { return () -> saveAndOpen(this, new SettingsScreen(false)); }
    }

    static String onOff(boolean on) { return on ? "on" : "off"; }

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
                    items.add(new MenuList.Item(() -> GameInput.deviceName(p) + "  " + Settings.ACTIONS[a],
                            () -> (waiting == index ? "press a key…" : Settings.keyName(app.settings().keys[p][a]))
                            + (waiting < 0 && index == refused ? note : ""), () -> {
                        app.input().consumeKey();
                        waiting = index;
                    }, null, ""));
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
            items.add(MenuList.Item.value("Arena", () -> arenaName(s.arena),
                    d -> s.arena = Settings.ARENA_CHOICES.get(Math.floorMod(Settings.ARENA_CHOICES.indexOf(s.arena) + d, Settings.ARENA_CHOICES.size()))));
            items.add(MenuList.Item.action("Host a game", this::host, "Match settings and rules apply to everyone."));
            for (int i = 0; i < LISTED; i++) {
                int index = i;
                items.add(new MenuList.Item(() -> {
                    var found = hosts;
                    if (index >= found.size()) return index == 0 ? "Searching for games on this network…" : "";
                    var h = found.get(index);
                    return "Join " + h.name() + "  ·  " + h.seats() + "/" + h.capacity() + (h.inLobby() ? "" : "  ·  playing");
                }, null, () -> {
                    var found = hosts;
                    if (index < found.size()) join(found.get(index).address(), found.get(index).port());
                }, null, ""));
            }
            items.add(MenuList.Item.action("Back", back()));
            return items;
        }

        @Override Runnable back() { return () -> open(new Main()); }

        private void host() {
            var s = app.settings();
            var arena = MatchState.loadArena(MatchState.pickArena(s.arena, 2, System.nanoTime())); // LAN rolls once per hosted game
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
        String name = id.equals(Settings.RANDOM) ? "Random" : MatchState.loadArena(id).name();
        return name + "  ·  up to " + MatchState.maxPlayers(id);
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
