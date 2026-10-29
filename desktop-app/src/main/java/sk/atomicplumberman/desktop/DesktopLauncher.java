package sk.atomicplumberman.desktop;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.system.AppSettings;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.network.Lan;
import sk.atomicplumberman.network.LanClient;
import sk.atomicplumberman.network.LanServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class DesktopLauncher extends SimpleApplication {
    static final String VERSION = java.util.Objects.requireNonNullElse(DesktopLauncher.class.getPackage().getImplementationVersion(), "dev");
    /** A LAN game in progress: our connection, and the server when this machine hosts. */
    record LanSession(LanServer host, LanClient client) { }

    private final boolean smokeTest;
    private final boolean diagnostics;
    private final boolean audioEnabled;
    private final String screenshotDirectory;
    private final String join;
    private final Settings settings;
    private LanSession session;
    private final GameInput input;
    private final Set<Long> shotTicks = new HashSet<>(List.of(36L, 172L, 230L));
    private final List<Supplier<AppState>> SMOKE_SCREENS = List.of(
            Screens.Main::new, () -> new Screens.SettingsScreen(false), Screens.RulesScreen::new, () -> new Screens.SettingsScreen(true), Screens.Controls::new,
            Screens.Help::new,
            Screens.LanMenu::new,
            () -> new LobbyState(new int[] {GameInput.KEYBOARD_A, GameInput.KEYBOARD_B}),
            // As the lobby does: a match attached mid-run is only initialised on the next frame.
            // A Bombman map in its own theme: textures, teleports and its own rules.
            () -> {
                settings().arena = "bm-islands";
                settings().theme = "map";
                var devices = java.util.stream.IntStream.range(0, 10).map(i -> i < 2 ? i : GameInput.FIRST_PAD + i - 2).toArray();
                return new MatchState(devices, 1, tick -> java.util.Collections.nCopies(10, PlayerInputFrame.NEUTRAL));
            },
            // Ten players on Classic Wide in another theme: identities, HUD strip, belts aside.
            () -> {
                settings().arena = "classic-wide";
                settings().theme = "bm-lab";
                var devices = java.util.stream.IntStream.range(0, 10).map(i -> i < 2 ? i : GameInput.FIRST_PAD + i - 2).toArray();
                return new MatchState(devices, 1, tick -> java.util.Collections.nCopies(10, PlayerInputFrame.NEUTRAL));
            });
    private ScreenshotAppState screenshots;
    private AudioMixer mixer;
    private float elapsed;
    private int pausedFrames = -1;
    private AppState smokeScreen;
    private boolean lanSmoke;
    private int lanFrames = -1;
    private final java.util.ArrayList<Float> frameTimes = new java.util.ArrayList<>();

    private DesktopLauncher(boolean smokeTest, boolean diagnostics, boolean audioEnabled, String screenshotDirectory,
            String join, Settings settings) {
        this.smokeTest = smokeTest;
        this.join = join;
        this.diagnostics = diagnostics;
        this.audioEnabled = audioEnabled;
        this.screenshotDirectory = screenshotDirectory;
        this.settings = settings;
        this.input = new GameInput(settings.keys);
    }

    public static void main(String[] args) {
        var options = Arrays.asList(args);
        String screenshotDirectory = null;
        String configDirectory = null;
        String join = null;
        int[] window = null; // fullscreen unless --window is given
        for (String arg : args) {
            if (arg.startsWith("--screenshot-dir=")) screenshotDirectory = arg.substring(17);
            else if (arg.startsWith("--config-dir=")) configDirectory = arg.substring(13);
            else if (arg.startsWith("--join=")) join = arg.substring(7);
            else if (arg.matches("--window=\\d+x\\d+")) {
                var size = arg.substring(9).split("x");
                window = new int[] {Integer.parseInt(size[0]), Integer.parseInt(size[1])};
            }
            else if (!List.of("--smoke-test", "--diagnostics", "--no-audio", "--no-vsync").contains(arg)) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        boolean smokeTest = options.contains("--smoke-test");
        if (smokeTest && configDirectory == null) {
            try { // the smoke test must not depend on, or write into, the player's own settings
                configDirectory = Files.createTempDirectory("atomic-plumberman-smoke").toString();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        var settings = Settings.load(Settings.defaultDirectory(configDirectory));
        if (smokeTest) {
            settings.arena = "wall-duel"; // the script shows a carried, thrown and bouncing bomb
            settings.theme = "map";
            settings.resetRules();
            settings.rules.put("speed", RulesetDefinition.CLASSIC.speed()); // the script counts ticks at 3 cells/s
        }
        boolean audio = !options.contains("--no-audio") && audioDevicePresent();
        var app = new DesktopLauncher(smokeTest, options.contains("--diagnostics"), audio, screenshotDirectory, join, settings);
        var display = new AppSettings(true);
        display.setTitle("Atomic Plumberman");
        if (window == null && !smokeTest) {
            display.setFullscreen(true);
            display.setWindowSize(0, 0); // 0: jME takes the monitor's current mode, i.e. its native resolution
        } else {
            display.setResolution(window == null ? 1280 : window[0], window == null ? 720 : window[1]);
        }
        display.setMinResolution(640, 480);
        display.setResizable(true);
        display.setUseRetinaFrameBuffer(true); // GLFW 3.4+: also scales the framebuffer on Wayland
        display.setRenderer(AppSettings.LWJGL_OPENGL33);
        display.setAudioRenderer(app.audioEnabled ? AppSettings.LWJGL_OPENAL : null);
        display.setUseJoysticks(true);
        // Wayland blocks vsynced buffer swaps while the window is hidden; an unattended smoke test must not stall.
        display.setVSync(!smokeTest && !options.contains("--no-vsync"));
        display.setFrameRate(144);
        display.setGammaCorrection(true);
        app.setSettings(display);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false); // the match pauses itself; menus keep rendering
        app.start();
    }

    /** jME's OpenAL renderer fails with a NullPointerException without a sound device; play silently instead. */
    private static boolean audioDevicePresent() {
        try {
            long device = org.lwjgl.openal.ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device != 0) org.lwjgl.openal.ALC10.alcCloseDevice(device);
            else System.err.println("AUDIO: no sound device, playing without sound");
            return device != 0;
        } catch (RuntimeException | LinkageError error) {
            System.err.println("AUDIO: OpenAL unavailable (" + error + "), playing without sound");
            return false;
        }
    }

    GameInput input() { return input; }
    LanSession session() { return session; }

    void startSession(LanServer host, LanClient client) {
        endSession();
        session = new LanSession(host, client);
    }

    void endSession() {
        if (session == null) return;
        session.client().close();
        if (session.host() != null) session.host().close();
        session = null;
    }

    @Override public void destroy() {
        endSession();
        super.destroy();
    }

    /** {@code --join=ADDRESS[:PORT]}: straight into that host's lobby. */
    private AppState joinFromCommandLine() {
        int colon = join.lastIndexOf(':');
        String address = colon > 0 ? join.substring(0, colon) : join;
        try {
            int port = colon > 0 ? Integer.parseInt(join.substring(colon + 1)) : Lan.PORT;
            startSession(null, LanClient.connect(address, port, Screens.playerName()));
            return new NetLobbyState();
        } catch (IOException | NumberFormatException e) {
            return new Screens.Notice("LAN GAME", "Cannot connect to " + join + ": " + e.getMessage());
        }
    }
    Settings settings() { return settings; }
    AudioMixer mixer() { return mixer; }

    void applyVolume() {
        if (listener != null) listener.setVolume(settings.volume / 100f);
        mixer.applyVolumes();
    }

    @Override public void simpleInitApp() {
        flyCam.setEnabled(false);
        viewPort.setBackgroundColor(new ColorRGBA(.025f, .04f, .065f, 1));
        setDisplayFps(false);
        setDisplayStatView(false);
        inputManager.setCursorVisible(true);
        mixer = new AudioMixer(assetManager, settings, rootNode, audioRenderer != null && !diagnostics);
        applyVolume();
        System.out.println("SETTINGS " + settings.directory.resolve("settings.properties"));
        if (diagnostics) {
            stateManager.attach(new TechnicalPreviewState(audioEnabled));
        } else {
            inputManager.deleteMapping(INPUT_MAPPING_EXIT); // Esc belongs to the game screens
            inputManager.addRawInputListener(input);
            inputManager.addJoystickConnectionListener(input);
            var pads = inputManager.getJoysticks();
            if (pads != null) Arrays.stream(pads).forEach(input::onConnected);
            stateManager.attach(smokeTest
                    ? new MatchState(new int[] {GameInput.KEYBOARD_A, GameInput.KEYBOARD_B}, 10, DesktopLauncher::smokeScript)
                    : join != null ? joinFromCommandLine() : new Screens.Main());
        }
        if (screenshotDirectory != null) {
            screenshots = new ScreenshotAppState(screenshotDirectory + "/", "atomic-plumberman");
            stateManager.attach(screenshots);
        }
    }

    /**
     * Wall Duel, seed 10. Both players walk one cell out of their corner and drop a bomb; P1 hides behind
     * the pillar, P2 lifts the bomb (Grab), carries it right and throws at tick 30: the flight wraps over
     * the edge and bounces off crates to land at (6, 11) on tick 54. P1's blast reveals a Kick at (3, 1).
     */
    private static List<PlayerInputFrame> smokeScript(long tick) {
        Direction p1 = tick < 20 ? Direction.RIGHT // 20 ticks at 3 cells/s = exactly one cell
                : tick > 20 && tick <= 40 ? Direction.LEFT : tick > 40 && tick <= 60 ? Direction.DOWN : Direction.NONE;
        Direction p2 = tick < 20 ? Direction.LEFT
                : tick > 20 && tick <= 40 ? Direction.RIGHT : tick > 40 && tick <= 60 ? Direction.UP : Direction.NONE;
        return List.of(new PlayerInputFrame(p1, tick == 20),
                new PlayerInputFrame(p2, tick == 20 || tick == 21, tick >= 21 && tick < 30, false));
    }

    private AppState startLanSmoke() {
        settings.arena = "classic";
        settings.theme = "map";
        var arena = MatchState.loadArena(settings.arena);
        try {
            var setup = new LanServer.Setup(arena, MatchState.rules(arena, settings), MatchState.themeId(arena, settings), false);
            var server = LanServer.start(0, "smoke", setup, 1_000_000_000L);
            startSession(server, LanClient.connect("127.0.0.1", server.port(), "smoke"));
            for (int device : new int[] {GameInput.KEYBOARD_A, GameInput.KEYBOARD_B}) {
                session.client().join(device, GameInput.deviceName(device));
                session.client().ready(device, true);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new NetLobbyState();
    }

    @Override public void loseFocus() {
        super.loseFocus();
        input.releaseAll();
        var match = stateManager.getState(MatchState.class);
        if (match != null && !smokeTest) match.focusLost();
    }

    @Override public void simpleUpdate(float tpf) {
        elapsed += tpf;
        if (smokeTest && elapsed > 1) frameTimes.add(tpf); // after shader compilation and the first sprite loads
        mixer.update(tpf);
        var match = stateManager.getState(MatchState.class);
        long tick = match == null ? -1 : match.roundTick();
        // Shots by simulation tick: bombs armed, explosion (bombs placed at tick 20), revealed pickups, then the pause screen.
        boolean shot = diagnostics ? elapsed - tpf < 1 && elapsed >= 1 : smokeTest && shotTicks.remove(tick);
        if (smokeTest && !diagnostics && tick >= 240 && pausedFrames < 0) {
            match.focusLost();
            pausedFrames = 0;
        } else if (pausedFrames >= 0 && pausedFrames <= 5 * SMOKE_SCREENS.size() + 5) {
            // Every 5 frames: shoot the current screen, then switch to the next menu screen.
            pausedFrames++;
            shot |= pausedFrames % 5 == 3;
            int next = pausedFrames / 5 - 1;
            if (pausedFrames % 5 == 0 && next < SMOKE_SCREENS.size()) {
                stateManager.detach(smokeScreen != null ? smokeScreen : match);
                smokeScreen = SMOKE_SCREENS.get(next).get();
                stateManager.attach(smokeScreen);
            }
        }
        if (smokeTest && !diagnostics && pausedFrames > 5 * SMOKE_SCREENS.size() + 5) {
            // Last: a LAN match through a real host and loopback socket; the round must pass the host's hash checks.
            if (!lanSmoke) {
                lanSmoke = true;
                stateManager.detach(smokeScreen);
                smokeScreen = startLanSmoke();
                stateManager.attach(smokeScreen);
            }
            if (lanFrames >= 0) lanFrames++;
            else if (match != null && match.roundTick() >= 90) {
                lanFrames = 0;
                shot = true;
            }
        }
        if (screenshots != null && shot) screenshots.takeScreenshot();
        if (smokeTest && elapsed > 30) handleError("smoke test timed out at tick " + tick, null);
        if (smokeTest && (diagnostics ? elapsed > 4 : lanFrames > 3)) {
            System.out.println("M0_SMOKE_OK: LWJGL scene, " + (diagnostics ? "GLB animation" : "scripted match round, pause and LAN round") + " and "
                    + (audioEnabled ? "OpenAL playback requested" : "audio explicitly disabled")
                    + "; physical input and audibility require manual confirmation.");
            if (!frameTimes.isEmpty()) {
                frameTimes.sort(null);
                System.out.printf(java.util.Locale.ROOT, "FRAMES %d at %dx%d without vsync: p95 %.1f ms, max %.1f ms%n", frameTimes.size(),
                        cam.getWidth(), cam.getHeight(), frameTimes.get(frameTimes.size() * 95 / 100) * 1000, frameTimes.getLast() * 1000);
            }
            stop();
        }
    }

    /**
     * Fatal error: the console gets the stack trace, the player a dialog naming the likely cause and a crash
     * log next to the settings, together with the replay of the round in progress.
     */
    @Override public void handleError(String message, Throwable error) {
        System.err.println("M0_FATAL: " + message);
        if (error != null) error.printStackTrace(System.err);
        stop();
        if (!smokeTest) {
            var match = stateManager == null ? null : stateManager.getState(MatchState.class);
            if (match != null) match.saveReplay();
            var log = settings.directory.resolve("crash.log");
            var trace = new java.io.StringWriter();
            if (error != null) error.printStackTrace(new java.io.PrintWriter(trace));
            try {
                Settings.writeAtomically(log, "Atomic Plumberman " + VERSION + ", Java " + System.getProperty("java.version") + ", "
                        + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n" + message + "\n" + trace);
            } catch (IOException ignored) {
                log = null; // the dialog still explains the error
            }
            String cause = error == null ? "" : String.valueOf(error.getMessage());
            String hint = cause.contains("OpenGL") || message.contains("display") || message.contains("context")
                    ? "The game needs a graphics driver with OpenGL 3.3. Update the driver or use another graphics card."
                    : cause.contains("OpenAL") || cause.contains("audio") ? "Start the game with --no-audio to play without sound."
                    : "Please report it with the crash log.";
            try {
                javax.swing.JOptionPane.showMessageDialog(null, message + (cause.isEmpty() ? "" : "\n" + cause) + "\n\n" + hint
                        + (log == null ? "" : "\nDetails: " + log), "Atomic Plumberman", javax.swing.JOptionPane.ERROR_MESSAGE);
            } catch (RuntimeException | Error noDisplay) {
                System.err.println(hint); // headless: the console is all there is
            }
        }
        System.exit(1);
    }
}
