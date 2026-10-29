package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import sk.atomicplumberman.content.ArenaJson;
import sk.atomicplumberman.content.ThemeDefinition;
import sk.atomicplumberman.content.ThemeJson;
import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.Match;
import sk.atomicplumberman.core.PlayerInputFrame;
import sk.atomicplumberman.core.Replay;
import sk.atomicplumberman.core.RulesetDefinition;
import sk.atomicplumberman.core.Simulation;
import sk.atomicplumberman.core.SimulationEvent;
import sk.atomicplumberman.core.SimulationRunner;
import sk.atomicplumberman.network.Lan;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Match for 2–10 players: countdown, rounds, results, rematch and pause, in the chosen arena and
 * theme. Ticks are scheduled by {@link SimulationRunner}; all outcomes come from the simulation.
 * In a LAN game the host sends the setup, round starts and every tick's inputs: this state runs the
 * same simulation from them, sends its own players' input and checks the host's state hash.
 */
final class MatchState extends BaseAppState {
    static final String ARENA = "Data/arenas/classic.json";
    private static final float MAX_FRAME = .25f;
    private static final float COUNTDOWN = 3;
    private static final float RESULT_SECONDS = 3;
    private static final float MARGIN = 20;

    private enum Phase { COUNTDOWN, PLAYING, ROUND_OVER, MATCH_OVER }

    private final int[] devices;
    private final long firstSeed;
    /** Scripted input for the smoke test, by round tick; null means humans play. */
    private final LongFunction<List<PlayerInputFrame>> autopilot;
    /** The host's match setup in a LAN game, null for a local match. */
    private final Lan.MatchStart lan;
    private final java.util.ArrayDeque<Lan.Message> inbox = new java.util.ArrayDeque<>();
    private int buffered;
    private boolean starving = true;
    private int[] sent;
    private List<Cell> starts;
    private final Node hud = new Node("HUD");
    private final Node pauseLayer = new Node("Pause");
    private final SimulationRunner runner = new SimulationRunner(8);
    private final List<List<PlayerInputFrame>> recording = new ArrayList<>();
    private DesktopLauncher app;
    private ArenaLayout arena;
    private ThemeDefinition theme;
    private List<Integer> teams;
    private boolean teamPlay;
    private RulesetDefinition rules;
    /** Per player: card, robot, name, win pips, kills and ability icons; see {@link #refreshHud}. */
    private com.jme3.ui.Picture[] cards;
    private com.jme3.ui.Picture[] robots;
    private BitmapText[] names;
    private BitmapText[] scores;
    private com.jme3.ui.Picture[][] pips;
    private Node[] iconRows;
    private float[] iconX;
    private float[] scoreRight;
    private float clockCenter;
    private BitmapText clock;
    private com.jme3.ui.Picture clockBar;
    private float clockBarWidth;
    /** The centre message as a banner on a dark band; it pops in whenever its text changes. */
    private com.jme3.ui.Picture message;
    private com.jme3.ui.Picture messageBand;
    private String messageText = "";
    private float messageAge;
    private BitmapText hint;
    private BitmapText diagnostics;
    /** Countdown and GO!: a dark band with the round number and one big word slamming in at the centre. */
    private final Node countdown = new Node("Countdown");
    private final Node countdownWord = new Node("Countdown word");
    private Geometry countdownBand;
    private com.jme3.ui.Picture countdownRound;
    private BitmapText pauseReason;
    private MenuList pauseMenu;
    private MenuList endMenu;
    private float shake;
    private Vector3f cameraHome;
    private Match match;
    private Simulation round;
    private ArenaScene scene;
    private Phase phase;
    private float timer;
    private long matchSeed;
    private boolean debug;
    private boolean paused;
    /** Set from the window callback, which may arrive before the first update has built the HUD. */
    private boolean focusLost;
    /** Slot whose device disappeared; the match stays paused until another device takes over. */
    private int orphan = -1;
    private int[] previousX;
    private int[] previousZ;
    private int width;
    private int height;

    MatchState(int[] devices, long seed, LongFunction<List<PlayerInputFrame>> autopilot) {
        this.devices = devices.clone();
        this.firstSeed = seed;
        this.autopilot = autopilot;
        this.lan = null;
    }

    /** LAN match; slots of client {@code me} are played by its local devices, the others are -1. */
    MatchState(Lan.MatchStart start, int me) {
        devices = start.players().stream().mapToInt(s -> s.client() == me ? s.local() : -1).toArray();
        firstSeed = start.setup().seed();
        autopilot = null;
        lan = start;
        sent = new int[devices.length];
        java.util.Arrays.fill(sent, -1);
    }

    @Override protected void initialize(Application application) {
        app = (DesktopLauncher) application;
        var settings = app.settings();
        if (lan != null) {
            var setup = lan.setup();
            arena = setup.arena();
            theme = loadTheme(lan.theme());
            teamPlay = lan.teamPlay();
            teams = setup.teams();
            rules = setup.rules();
            starts = setup.starts();
        } else {
            arena = loadArena(pickArena(settings.arena, devices.length, firstSeed));
            theme = loadTheme(themeId(arena, settings));
            // Team play pairs slots across the arena: P1 + P3 against P2 + P4 (Wall Duel: left against right).
            teamPlay = (arena.teams() || settings.teams) && devices.length > 2;
            teams = java.util.stream.IntStream.range(0, devices.length).map(i -> teamPlay ? i % 2 : i).boxed().toList();
            rules = rules(arena, settings);
            starts = arena.starts().subList(0, devices.length);
        }
        var host = lan == null ? null : app.session().host();
        pauseMenu = new MenuList(lan == null ? List.of(
                MenuList.Item.action("Resume", this::resume),
                MenuList.Item.action("Quit to menu", () -> open(new Screens.Main())))
                : List.of(
                MenuList.Item.action("Back to the game", this::resume),
                MenuList.Item.action(host != null ? "End the LAN game" : "Leave the LAN game", () -> leave(null))));
        endMenu = new MenuList(lan == null ? List.of(
                MenuList.Item.action("Rematch", () -> {
                    matchSeed++;
                    startMatch();
                }),
                MenuList.Item.action("Change players", () -> open(new LobbyState(devices))),
                MenuList.Item.action("Main menu", () -> open(new Screens.Main())))
                : host != null ? List.of(
                MenuList.Item.action("Rematch", host::rematch),
                MenuList.Item.action("Back to the LAN lobby", host::toLobby),
                MenuList.Item.action("End the LAN game", () -> leave(null)))
                : List.of(MenuList.Item.action("Leave the LAN game", () -> leave(null))));
        matchSeed = firstSeed;
        startMatch();
    }

    /** The rules of a match from the menus: base rules, then the arena's own changes. */
    static RulesetDefinition rules(ArenaLayout arena, Settings settings) {
        var base = RulesetDefinition.CLASSIC;
        for (var rule : settings.rules.entrySet()) base = base.with(rule.getKey(), rule.getValue());
        return arena.rules(base
                .with("roundTicks", settings.roundSeconds * Simulation.TICKS_PER_SECOND)
                .with("winsToWin", settings.winsToWin)
                .with("killsToWin", settings.killsMode ? settings.killsToWin : 0)
                .with("teamDamage", settings.teamDamage)
                .with("suddenDeathTicks", settings.suddenDeath ? RulesetDefinition.CLASSIC.suddenDeathTicks() : 0));
    }

    static String themeId(ArenaLayout arena, Settings settings) {
        return !settings.theme.equals("map") ? settings.theme : arena.theme().isEmpty() ? "bm-tiles" : arena.theme();
    }

    private static final java.util.Map<String, ArenaLayout> ARENAS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, ThemeDefinition> THEMES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Arenas and themes are immutable, so menus can ask for names every frame. */
    static ArenaLayout loadArena(String id) { return ARENAS.computeIfAbsent(id, MatchState::readArena); }
    static ThemeDefinition loadTheme(String id) { return THEMES.computeIfAbsent(id, MatchState::readTheme); }

    /** {@link Settings#RANDOM} becomes an arena with room for {@code players}, chosen by {@code seed}; other ids stay. */
    static String pickArena(String id, int players, long seed) {
        if (!id.equals(Settings.RANDOM)) return id;
        var fitting = Settings.ARENAS.stream().filter(a -> loadArena(a).starts().size() >= players).toList();
        return fitting.get(new java.util.Random(seed).nextInt(fitting.size()));
    }

    /** Players the arena takes; for {@link Settings#RANDOM} the largest arena. */
    static int maxPlayers(String id) {
        return id.equals(Settings.RANDOM) ? Settings.ARENAS.stream().mapToInt(a -> loadArena(a).starts().size()).max().orElseThrow()
                : loadArena(id).starts().size();
    }

    private static ArenaLayout readArena(String id) {
        String path = "Data/arenas/" + id + ".json";
        try (var stream = Objects.requireNonNull(MatchState.class.getClassLoader().getResourceAsStream(path), path);
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return ArenaJson.read(reader, path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    private static ThemeDefinition readTheme(String id) {
        String path = "Data/themes/" + id + ".json";
        try (var stream = Objects.requireNonNull(MatchState.class.getClassLoader().getResourceAsStream(path), path);
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return ThemeJson.read(reader, path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    /** Sound of a simulation event; explosions are handled per tick. */
    private static String sound(SimulationEvent.Type type) {
        return switch (type) {
            case BOMB_PLACED, CELL_CLOSED -> "place";
            case ITEM_PICKED -> "pickup";
            case PLAYER_DIED -> "death";
            case BOMB_KICKED -> "kick";
            case BOMB_PUNCHED -> "punch";
            case BOMB_THROWN -> "throw";
            case BOMB_LANDED -> "land";
            case PLAYER_STUNNED -> "stun";
            case PLAYER_INFECTED -> "infect";
            case PLAYER_WARPED -> "warp";
            default -> null;
        };
    }

    /** Round tick, or -1 while the state is attached but not yet initialised by jME. */
    long roundTick() { return round == null ? -1 : round.tick(); }

    private String teamName(int team) {
        return teamPlay ? "TEAM " + (char) ('A' + team) : "P" + (team + 1);
    }

    private void startMatch() {
        match = new Match(arena, starts, teams, rules, matchSeed);
        if (lan == null) startRound(); // a LAN round starts when the host says so
    }

    // ------------------------------------------------------------ LAN

    /**
     * Moves the host's messages into the inbox. Frames wait there for their tick; a new match, the lobby or
     * a lost connection take over at once and leave later messages to the next state.
     */
    private void pump() {
        var client = app.session().client();
        for (var m = client.poll(); m != null; m = client.poll()) {
            switch (m) {
                case Lan.MatchStart next -> {
                    open(new MatchState(next, client.id()));
                    return;
                }
                case Lan.Lobby lobby -> {
                    open(new NetLobbyState(lobby));
                    return;
                }
                case Lan.Closed closed -> {
                    leave(closed.reason());
                    return;
                }
                case Lan.Frame f -> {
                    buffered++;
                    inbox.add(f);
                }
                default -> inbox.add(m);
            }
        }
        control();
    }

    /** Round starts and hash checks up to the next frame; they follow the frames before them. */
    private void control() {
        while (isEnabled() && !inbox.isEmpty() && !(inbox.peek() instanceof Lan.Frame)) {
            switch (inbox.poll()) {
                case Lan.Round r -> {
                    if (round != null && !round.isOver()) {
                        desync("round " + r.number() + " started early");
                        return;
                    }
                    startRound();
                    if (match.rounds() != r.number()) desync("round " + r.number() + " but here " + match.rounds());
                }
                case Lan.Hash h -> {
                    if (h.tick() != round.tick() || h.hash() != round.stateHash()) desync("state hash at tick " + h.tick());
                }
                default -> { }
            }
        }
    }

    /** Frames from the host on this machine's tick clock, with a two-tick cushion against network jitter. */
    private void advanceLan() {
        long now = System.nanoTime();
        if (starving) {
            if (buffered < 2) return;
            starving = false;
            runner.resume(now);
        }
        runner.advance(now, this::lanTick);
        if (runner.consumeStall()) starving = true;
        while (buffered > 6 && phase == Phase.PLAYING && isEnabled()) lanTick(); // drift back after a hitch
    }

    private void lanTick() {
        if (phase != Phase.PLAYING || !isEnabled()) return;
        if (!(inbox.peek() instanceof Lan.Frame frame)) {
            starving = true;
            runner.pause();
            return;
        }
        inbox.poll();
        buffered--;
        if (frame.tick() != round.tick()) {
            desync("frame for tick " + frame.tick() + " at " + round.tick());
            return;
        }
        tick(frame.inputs());
        control();
    }

    /** Own players' input goes to the host whenever it changes; presses always change it. */
    private void sendInputs() {
        for (int slot = 0; slot < devices.length; slot++) {
            if (devices[slot] < 0) continue;
            var frame = paused ? PlayerInputFrame.NEUTRAL : app.input().frames(new int[] {devices[slot]}).getFirst();
            int packed = Lan.pack(frame);
            if (packed == sent[slot]) continue;
            app.session().client().input(slot, frame);
            sent[slot] = packed;
        }
    }

    private void desync(String detail) {
        System.err.println("LAN: out of sync with the host: " + detail);
        leave("This game went out of sync with the host (" + detail + ").\nPlease report it with the replay in "
                + app.settings().directory.resolve("replays") + ".");
    }

    /** Ends the LAN session; with a reason the player sees why. */
    private void leave(String reason) {
        if (reason != null) saveReplay();
        app.endSession();
        open(reason == null ? new Screens.Main() : new Screens.Notice("LAN GAME", reason));
    }

    private void startRound() {
        round = match.nextRound();
        System.out.printf("ROUND %d match-seed=%d round-seed=%d%n", match.rounds(), matchSeed, round.seed());
        recording.clear();
        previousX = round.players().stream().mapToInt(Simulation.Player::x).toArray();
        previousZ = round.players().stream().mapToInt(Simulation.Player::z).toArray();
        if (scene != null) scene.root.removeFromParent();
        scene = new ArenaScene(app.getAssetManager(), Ui.font(app.getAssetManager(), 32), round, theme, teamPlay, app.settings().flicker);
        if (isEnabled()) app.getRootNode().attachChild(scene.root);
        phase = Phase.COUNTDOWN;
        timer = COUNTDOWN;
        if (width > 0) layoutCountdown(); // shows the new round number
        app.input().clearEdges();
        app.mixer().effect("ui-confirm");
    }

    @Override public void update(float tpf) {
        var input = app.input();
        if (width != app.getCamera().getWidth() || height != app.getCamera().getHeight()) layout();
        if (lan != null) {
            pump();
            if (!isEnabled() || round == null) return; // left, or the first round has not started yet
        }
        if (input.consume(GameInput.Menu.DEBUG)) debug = !debug;
        if (focusLost) {
            focusLost = false;
            if (!paused && lan == null) pause("Paused because the window lost focus.");
        }
        for (int slot = 0; slot < devices.length && orphan < 0 && autopilot == null && lan == null; slot++) {
            if (!input.connected(devices[slot])) {
                orphan = slot;
                pause(String.format("P%d: %s disconnected.%nPress the bomb button of a free device to take over P%d.",
                        slot + 1, GameInput.deviceName(devices[slot]), slot + 1));
            }
        }
        boolean back = input.consume(GameInput.Menu.BACK); // gamepad B is Action in play, so only PAUSE pauses
        if (paused) {
            updatePause(back);
        } else if (input.consume(GameInput.Menu.PAUSE)) {
            pause(lan == null ? "" : "The LAN game goes on while this menu is open.");
        }
        if (lan != null && phase == Phase.PLAYING) sendInputs();
        if (!paused || lan != null) { // a LAN game cannot stop for one player
            float frame = Math.min(tpf, MAX_FRAME);
            switch (phase) {
                case COUNTDOWN -> {
                    int before = (int) Math.ceil(timer);
                    timer -= frame;
                    if (timer <= 0) {
                        phase = Phase.PLAYING;
                        input.clearEdges();
                        if (lan == null) runner.resume(System.nanoTime());
                        starving = true;
                        app.mixer().effect("go");
                    } else if ((int) Math.ceil(timer) != before) {
                        app.mixer().effect("ui-confirm");
                    }
                }
                case PLAYING -> {
                    if (lan != null) {
                        advanceLan();
                        if (!isEnabled()) return;
                    } else {
                        runner.advance(System.nanoTime(), () -> tick(autopilot != null ? autopilot.apply(round.tick())
                                : app.input().frames(devices)));
                        if (runner.consumeStall()) pause("The game fell more than 250 ms behind and was paused.");
                    }
                }
                case ROUND_OVER -> { // a LAN game waits for the host's next round
                    timer -= frame;
                    if (lan == null && (timer <= 0 || input.consume(GameInput.Menu.CONFIRM))) startRound();
                }
                case MATCH_OVER -> {
                    if (!paused) endMenu.update(input, app.mixer());
                    if (!isEnabled()) return;
                }
            }
        }
        boolean still = paused && lan == null;
        float alpha = phase == Phase.PLAYING && !still ? runner.alpha(System.nanoTime()) : 1;
        scene.update(still ? 0 : tpf, previousX, previousZ, alpha, debug);
        shakeCamera(still ? 0 : tpf);
        refreshHud(tpf);
    }

    /** Optional and purely visual (plan 3.2): never touches input mapping or collisions. */
    private void shakeCamera(float tpf) {
        if (cameraHome == null) return;
        shake = Math.max(0, shake - tpf * .6f);
        var offset = new Vector3f((float) Math.random() - .5f, 0, (float) Math.random() - .5f).multLocal(shake);
        app.getCamera().setLocation(cameraHome.add(offset));
    }

    private void tick(List<PlayerInputFrame> frames) {
        for (var p : round.players()) {
            previousX[p.id()] = p.x();
            previousZ[p.id()] = p.z();
        }
        recording.add(frames);
        match.step(frames);
        scene.onEvents(round.events());
        long explosions = round.events().stream().filter(e -> e.type() == SimulationEvent.Type.EXPLOSION).count();
        if (explosions > 0) {
            app.mixer().effect(explosions > 1 ? "chain" : "explosion"); // one sound per tick, bigger for chains
            if (app.settings().cameraShake) shake = Math.min(.25f, shake + .06f * explosions);
        }
        for (var event : round.events()) {
            String name = sound(event.type());
            if (name != null) app.mixer().effect(name);
        }
        long left = rules.roundTicks() - round.tick();
        if (left == rules.suddenDeathTicks() || left == 10L * Simulation.TICKS_PER_SECOND) app.mixer().effect("warning");
        if (!round.isOver() && !match.isOver()) return;
        runner.pause();
        saveReplay();
        app.input().clearEdges();
        if (match.isOver()) {
            phase = Phase.MATCH_OVER;
            app.mixer().effect("fanfare");
        } else {
            phase = Phase.ROUND_OVER;
            timer = RESULT_SECONDS;
        }
    }

    /** The last round's inputs, enough to reproduce it bit for bit with {@link Replay#decode}; also after a crash. */
    void saveReplay() {
        if (round == null) return;
        var file = app.settings().directory.resolve("replays").resolve("last-round.replay");
        try {
            var replay = new Replay(arena, starts, teams, rules, round.seed(), recording);
            Settings.writeAtomically(file, replay.encode());
            System.out.println("REPLAY " + file);
        } catch (IOException exception) {
            System.err.println("REPLAY: cannot save " + file + " (" + exception.getMessage() + ")");
        }
    }

    private void pause(String reason) {
        if (!paused) {
            paused = true;
            if (lan == null) runner.pause();
            app.input().clearEdges();
        }
        pauseReason.setText(reason);
        pauseReason.setLocalTranslation((width - pauseReason.getLineWidth()) / 2, pauseReason.getLocalTranslation().y, 1);
        app.getGuiNode().attachChild(pauseLayer);
    }

    /** Window focus loss pauses the match at the next update; held input has already been released. */
    void focusLost() {
        focusLost = true;
    }

    private void updatePause(boolean back) {
        var input = app.input();
        if (orphan >= 0) {
            for (int device : input.devices()) {
                if (input.consumeBomb(device) && !contains(devices, device)) {
                    devices[orphan] = device;
                    System.out.printf("P%d now uses %s%n", orphan + 1, GameInput.deviceName(device));
                    orphan = -1;
                    pause("Controls reassigned. Resume when ready.");
                    break;
                }
            }
        }
        if (input.consume(GameInput.Menu.PAUSE) || back) resume();
        else pauseMenu.update(input, app.mixer());
    }

    private void resume() {
        if (orphan >= 0 || !paused) return; // a slot without a device cannot play
        paused = false;
        pauseLayer.removeFromParent();
        app.input().clearEdges();
        if (phase == Phase.PLAYING && lan == null) runner.resume(System.nanoTime());
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) if (v == value) return true;
        return false;
    }

    private void open(BaseAppState next) {
        getStateManager().detach(this);
        getStateManager().attach(next);
    }

    /**
     * The top bar: a card per player in their colour (team colour in team play), five per row, with their robot,
     * round wins as gold pips (kills in Kills mode), kills and ability icons; eliminated players fade. The clock
     * sits in a pill on the right above a draining bar that turns red and pulses in sudden death.
     */
    private void refreshHud(float tpf) {
        float s = Ui.scale(app.getCamera());
        float time = System.nanoTime() / 1e9f;
        boolean killsMode = rules.killsToWin() > 0;
        for (int i = 0; i < devices.length; i++) {
            var p = round.players().get(i);
            int team = match.team(i);
            int kills = killsMode && teamPlay ? match.teamKills(team) : match.kills(i);
            scores[i].setText(killsMode ? "K " + kills + "/" + rules.killsToWin() : "K " + kills);
            scores[i].setLocalTranslation(scoreRight[i] - scores[i].getLineWidth(), scores[i].getLocalTranslation().y, 1);
            for (int w = 0; w < pips[i].length; w++) {
                Ui.tint(pips[i][w], w < match.wins(team) ? new ColorRGBA(1, .8f, .25f, 1) : new ColorRGBA(1, 1, 1, .2f));
            }
            boolean alive = p.alive();
            Ui.tint(cards[i], new ColorRGBA(1, 1, 1, alive ? 1 : .45f));
            Ui.tint(robots[i], alive ? ColorRGBA.White : new ColorRGBA(.35f, .35f, .4f, .7f));
            names[i].setAlpha(alive ? 1 : .4f);
            scores[i].setAlpha(alive ? 1 : .4f);
            float cardBottom = robots[i].getLocalTranslation().y - 2 * s, cardHeight = robots[i].getHeight() / 1.15f;
            icons(i, p, iconX[i], cardBottom + (cardHeight - 13 * s) / 2, 13 * s);
        }
        long left = Math.max(0, rules.roundTicks() - round.tick());
        long seconds = (left + Simulation.TICKS_PER_SECOND - 1) / Simulation.TICKS_PER_SECOND;
        clock.setText(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        boolean closing = rules.suddenDeathTicks() > 0 && left <= rules.suddenDeathTicks();
        float beat = closing && phase == Phase.PLAYING ? .5f + .5f * (float) Math.sin(time * 8) : 0;
        clock.setColor(closing ? new ColorRGBA(1, .35f + .4f * beat, .3f + .4f * beat, 1) : ColorRGBA.White);
        clock.setLocalTranslation(clockCenter - clock.getLineWidth() / 2, clock.getLocalTranslation().y, 1);
        float share = (float) left / rules.roundTicks();
        clockBar.setWidth(Math.max(1, clockBarWidth * share));
        Ui.tint(clockBar, closing ? new ColorRGBA(1, .25f + .3f * beat, .2f, 1)
                : share < .4f ? new ColorRGBA(1, .7f, .2f, 1) : new ColorRGBA(.35f, .9f, .6f, 1));
        String winner = round.winner() >= 0 ? teamName(round.winner()) : null;
        showMessage(switch (phase) {
            case COUNTDOWN -> "";
            case PLAYING -> round.tick() < Simulation.TICKS_PER_SECOND / 2 ? ""
                    : closing && left > rules.suddenDeathTicks() - 2L * Simulation.TICKS_PER_SECOND ? "SUDDEN DEATH" : "";
            case ROUND_OVER -> winner == null ? "DRAW" : winner + " WINS THE ROUND";
            case MATCH_OVER -> match.champion() == Match.DRAW ? "DRAW — TIED ON KILLS" : teamName(match.champion()) + " WINS THE MATCH";
        }, switch (phase) {
            case ROUND_OVER -> round.winner();
            case MATCH_OVER -> match.champion();
            default -> -1;
        }, tpf);
        refreshCountdown();
        endMenu.node.setCullHint(phase == Phase.MATCH_OVER && !paused ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        diagnostics.setCullHint(debug ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        if (debug) {
            var text = new StringBuilder(String.format(Locale.ROOT,
                    "tick %d  round %d  seed %d%nhash %016x%nfps %.0f  lag %.1f ms%n", round.tick(), match.rounds(), round.seed(),
                    round.stateHash(), app.getTimer().getFrameRate(), runner.lagNanos(System.nanoTime()) / 1e6));
            for (var p : round.players()) {
                text.append(String.format(Locale.ROOT, "P%d  %s  x %.3f  z %.3f%n", p.id() + 1,
                        devices[p.id()] < 0 ? "LAN" : GameInput.deviceName(devices[p.id()]), p.x() / (float) Simulation.SUB, p.z() / (float) Simulation.SUB));
            }
            for (var b : round.bombs()) {
                text.append(String.format(Locale.ROOT, "bomb #%d  P%d  %d,%d  %d ticks%n",
                        b.id(), b.owner() + 1, b.x(), b.z(), b.explodesAt() - round.tick()));
            }
            diagnostics.setText(text.toString());
            diagnostics.setLocalTranslation(MARGIN * s, height * .78f, 0);
        }
    }

    /**
     * Shows {@code text} as a banner: in the colours of {@code owner} (a slot or team, -1 for none), gold for the
     * match, red for sudden death. A new text pops in from 1.6× over a third of a second.
     */
    private void showMessage(String text, int owner, float tpf) {
        if (!text.equals(messageText)) {
            messageText = text;
            messageAge = 0;
            if (!text.isEmpty()) {
                var c = owner < 0 || owner == Match.DRAW ? new ColorRGBA(.7f, .75f, .85f, 1)
                        : teamPlay ? ArenaScene.TEAM_COLORS[owner % ArenaScene.TEAM_COLORS.length]
                        : ArenaScene.PLAYER_COLORS[owner % ArenaScene.PLAYER_COLORS.length];
                java.awt.Color top = mix(c, .75f), bottom = mix(c, .25f);
                if (text.equals("SUDDEN DEATH")) {
                    top = new java.awt.Color(255, 190, 120);
                    bottom = new java.awt.Color(215, 30, 30);
                } else if (phase == Phase.MATCH_OVER && owner >= 0 && owner != Match.DRAW) {
                    top = new java.awt.Color(255, 245, 170);
                    bottom = new java.awt.Color(235, 150, 20);
                }
                var texture = Ui.banner(text, Math.round(64 * Ui.scale(app.getCamera())), top, bottom);
                message.setTexture(app.getAssetManager(), texture, true);
            }
        }
        messageAge += tpf;
        boolean shown = !text.isEmpty();
        message.setCullHint(shown ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        messageBand.setCullHint(shown ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        if (!shown) return;
        var image = message.getMaterial().getTextureParam("Texture").getTextureValue().getImage();
        float pop = 1 + .6f * (float) Math.pow(Math.max(0, 1 - messageAge / .35f), 3);
        float centerY = phase == Phase.MATCH_OVER ? height * .7f : height / 2f;
        message.setWidth(image.getWidth() * pop);
        message.setHeight(image.getHeight() * pop);
        message.setPosition((width - message.getWidth()) / 2, centerY - message.getHeight() / 2);
        messageBand.setPosition(0, centerY - messageBand.getHeight() / 2);
        Ui.tint(messageBand, new ColorRGBA(1, 1, 1, Math.min(1, messageAge / .2f)));
    }

    /** A colour lightened towards white by {@code white}. */
    private static java.awt.Color mix(ColorRGBA c, float white) {
        return new java.awt.Color(c.r + (1 - c.r) * white, c.g + (1 - c.g) * white, c.b + (1 - c.b) * white);
    }

    /**
     * Each second its number slams in from 1.8× with a tilt, then fades; 3 red, 2 orange, 1 yellow. GO! grows
     * and fades over its half second. The band keeps the word readable on any arena.
     */
    private void refreshCountdown() {
        boolean go = phase == Phase.PLAYING && round.tick() < Simulation.TICKS_PER_SECOND / 2;
        if (phase != Phase.COUNTDOWN && !go) {
            countdown.setCullHint(Spatial.CullHint.Always);
            return;
        }
        countdown.setCullHint(Spatial.CullHint.Inherit);
        int n = (int) Math.ceil(timer);
        float age = go ? round.tick() / (Simulation.TICKS_PER_SECOND / 2f) : Math.clamp(n - timer, 0, 1);
        java.awt.Color[] colors = go ? new java.awt.Color[] {new java.awt.Color(190, 255, 150), new java.awt.Color(30, 175, 70)}
                : switch (n) {
                    case 3 -> new java.awt.Color[] {new java.awt.Color(255, 140, 120), new java.awt.Color(205, 25, 40)};
                    case 2 -> new java.awt.Color[] {new java.awt.Color(255, 215, 120), new java.awt.Color(235, 110, 15)};
                    default -> new java.awt.Color[] {new java.awt.Color(255, 250, 170), new java.awt.Color(235, 185, 20)};
                };
        var word = (com.jme3.ui.Picture) countdownWord.getChild(0);
        var texture = Ui.banner(go ? "GO!" : String.valueOf(n), Math.round(190 * Ui.scale(app.getCamera())), colors[0], colors[1]);
        word.setTexture(app.getAssetManager(), texture, true);
        word.setWidth(texture.getImage().getWidth());
        word.setHeight(texture.getImage().getHeight());
        word.setPosition(-texture.getImage().getWidth() / 2f, -texture.getImage().getHeight() / 2f);
        float slam = (float) Math.pow(1 - age, 4);
        float scale = go ? 1 + .5f * age : 1 + .8f * slam;
        float alpha = go ? 1 - age * age : age < .8f ? 1 : (1 - age) / .2f;
        countdownWord.setLocalScale(scale);
        countdownWord.setLocalRotation(new com.jme3.math.Quaternion().fromAngleAxis((n % 2 == 0 ? -.3f : .3f) * slam,
                Vector3f.UNIT_Z));
        word.getMaterial().setColor("Color", new ColorRGBA(1, 1, 1, alpha));
        countdownRound.getMaterial().setColor("Color", new ColorRGBA(1, 1, 1, go ? 1 - age : 1));
        countdownBand.getMaterial().setColor("Color", new ColorRGBA(.03f, .02f, .06f, .7f * (go ? 1 - age : 1)));
    }

    /** A row of Bombman ability icons from (x, y), plus the disease icon while sick; rebuilt only when it changes. */
    private void icons(int slot, Simulation.Player p, float x, float y, float size) {
        var paths = new ArrayList<String>();
        for (var item : sk.atomicplumberman.core.Item.values()) {
            if (item.ability() && p.has(item) && ArenaScene.iconSprite(item) != null) paths.add(ArenaScene.iconSprite(item));
        }
        if (!p.effects().isEmpty()) paths.add(ArenaScene.iconSprite(sk.atomicplumberman.core.Item.DISEASE));
        String key = paths + "@" + size;
        if (!key.equals(iconRows[slot].getUserData("key"))) {
            iconRows[slot].detachAllChildren();
            for (int k = 0; k < paths.size(); k++) {
                var texture = app.getAssetManager().loadTexture(paths.get(k));
                texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
                var picture = new com.jme3.ui.Picture("Icon");
                picture.setTexture(app.getAssetManager(), (com.jme3.texture.Texture2D) texture, true);
                picture.setWidth(size * 12 / 11f);
                picture.setHeight(size);
                picture.setPosition(k * size * 1.2f, 0);
                iconRows[slot].attachChild(picture);
            }
            iconRows[slot].setUserData("key", key);
        }
        iconRows[slot].setLocalTranslation(x, y, 0);
        iconRows[slot].setCullHint(p.alive() ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    /** Rebuilds all GUI text at the pixel size of the current window, so it stays sharp. */
    private void layout() {
        width = app.getCamera().getWidth();
        height = app.getCamera().getHeight();
        var camera = app.getCamera();
        var assets = app.getAssetManager();
        float[] frame = ArenaScene.bounds(arena.width(), arena.height());
        OrthographicArenaCamera.fit(camera, frame[0], frame[1], frame[2], frame[3]);
        cameraHome = camera.getLocation().clone();
        hud.detachAllChildren();
        float s = Ui.scale(camera);
        float m = MARGIN * s;
        int n = devices.length;
        cards = new com.jme3.ui.Picture[n];
        robots = new com.jme3.ui.Picture[n];
        names = new BitmapText[n];
        scores = new BitmapText[n];
        pips = new com.jme3.ui.Picture[n][];
        iconRows = new Node[n];
        iconX = new float[n];
        scoreRight = new float[n];
        // Five cards per row left of the clock pill; with more than five players two slimmer rows.
        float cardHeight = (n > 5 ? 31 : 40) * s;
        float gap = 6 * s;
        float clockWidth = 132 * s;
        float cardWidth = (width - 2 * m - clockWidth - gap) / 5 - gap;
        var dot = Ui.dot(Math.round(9 * s));
        for (int i = 0; i < n; i++) {
            float x = m + (i % 5) * (cardWidth + gap);
            float bottom = height - m * .5f - cardHeight - (i / 5) * (cardHeight + gap);
            var c = teamPlay ? ArenaScene.TEAM_COLORS[teams.get(i) % ArenaScene.TEAM_COLORS.length] : ArenaScene.PLAYER_COLORS[i];
            cards[i] = Ui.picture(assets, Ui.panel(Math.round(cardWidth), Math.round(cardHeight), Math.round(9 * s), Ui.awt(18, 20, 36, 220),
                    Ui.awt(Math.round(55 + c.r * 200), Math.round(55 + c.g * 200), Math.round(55 + c.b * 200), 235), 2 * s), x, bottom);
            hud.attachChild(cards[i]);
            robots[i] = new com.jme3.ui.Picture("Robot");
            robots[i].setTexture(assets, ArenaScene.robot("player_down", i), true);
            robots[i].setHeight(cardHeight * 1.15f);
            robots[i].setWidth(cardHeight * 1.15f * 60 / 94);
            robots[i].setPosition(x + 5 * s, bottom + 2 * s);
            robots[i].setLocalTranslation(robots[i].getLocalTranslation().x, robots[i].getLocalTranslation().y, .5f);
            hud.attachChild(robots[i]);
            names[i] = Ui.text(assets, camera, n > 5 ? 15 : 17, ArenaScene.textColor(i));
            names[i].setText("P" + (i + 1) + (teamPlay ? " " + (char) ('A' + teams.get(i)) : ""));
            float textY = bottom + (cardHeight + names[i].getLineHeight()) / 2;
            float nameX = x + 9 * s + robots[i].getWidth();
            names[i].setLocalTranslation(nameX, textY, 1);
            hud.attachChild(names[i]);
            int wins = rules.killsToWin() > 0 ? 0 : rules.winsToWin();
            pips[i] = new com.jme3.ui.Picture[wins];
            float pipX = nameX + names[i].getLineWidth() + 7 * s;
            for (int w = 0; w < wins; w++) {
                pips[i][w] = Ui.picture(assets, dot, pipX + w * 12 * s, bottom + (cardHeight - dot.getImage().getHeight()) / 2);
                pips[i][w].setLocalTranslation(pips[i][w].getLocalTranslation().x, pips[i][w].getLocalTranslation().y, 1);
                hud.attachChild(pips[i][w]);
            }
            iconX[i] = pipX + wins * 12 * s + (wins > 0 ? 4 : 0) * s;
            scores[i] = Ui.text(assets, camera, n > 5 ? 13 : 15, new ColorRGBA(.85f, .88f, .95f, 1));
            scores[i].setLocalTranslation(0, textY - (names[i].getLineHeight() - scores[i].getLineHeight()) / 2, 1);
            scoreRight[i] = x + cardWidth - 8 * s;
            hud.attachChild(scores[i]);
            iconRows[i] = new Node("Icons " + (i + 1));
            iconRows[i].setLocalTranslation(0, 0, 1);
            hud.attachChild(iconRows[i]);
        }
        float pillHeight = n > 5 ? 2 * cardHeight + gap : cardHeight;
        float pillX = width - m - clockWidth;
        float pillBottom = height - m * .5f - pillHeight;
        hud.attachChild(Ui.picture(assets, Ui.panel(Math.round(clockWidth), Math.round(pillHeight), Math.round(pillHeight / 2.5f),
                Ui.awt(18, 20, 36, 220), Ui.awt(140, 150, 180, 200), 2 * s), pillX, pillBottom));
        clockCenter = pillX + clockWidth / 2;
        clock = Ui.text(assets, camera, n > 5 ? 38 : 28, ColorRGBA.White);
        clock.setLocalTranslation(0, pillBottom + (pillHeight + clock.getLineHeight()) / 2 + 2 * s, 1);
        clockBarWidth = clockWidth - 36 * s;
        var track = Ui.panel(Math.round(clockBarWidth), Math.max(2, Math.round(4 * s)), Math.round(2 * s), Ui.awt(255, 255, 255, 40), null, 0);
        hud.attachChild(Ui.picture(assets, track, pillX + 18 * s, pillBottom + 5 * s));
        clockBar = Ui.picture(assets, Ui.panel(Math.round(clockBarWidth), Math.max(2, Math.round(4 * s)), Math.round(2 * s),
                java.awt.Color.WHITE, null, 0), pillX + 18 * s, pillBottom + 5 * s);
        clockBar.setLocalTranslation(clockBar.getLocalTranslation().x, clockBar.getLocalTranslation().y, 1);
        hud.attachChild(clockBar);
        messageBand = Ui.picture(assets, Ui.panel(width, Math.round(120 * s), 0, Ui.awt(6, 4, 14, 175), null, 0), 0, 0);
        message = new com.jme3.ui.Picture("Message");
        message.setTexture(assets, Ui.banner(" ", 8, java.awt.Color.WHITE, java.awt.Color.WHITE), true);
        message.setLocalTranslation(0, 0, 1);
        messageText = "";
        hint = Ui.text(assets, camera, 14, new ColorRGBA(.7f, .75f, .8f, 1));
        hint.setText(lan == null ? "Esc / Start: pause     F3: diagnostics" : "Esc / Start: menu (the LAN game goes on)     F3: diagnostics");
        hint.setLocalTranslation((width - hint.getLineWidth()) / 2, 4 * Ui.scale(camera) + hint.getLineHeight(), 0);
        diagnostics = Ui.text(assets, camera, 14, new ColorRGBA(.6f, 1, .7f, 1));
        endMenu.layout(assets, camera, width / 2f, height * .5f);
        hud.attachChild(clock);
        hud.attachChild(messageBand);
        hud.attachChild(message);
        hud.attachChild(hint);
        hud.attachChild(diagnostics);
        endMenu.node.setLocalTranslation(0, 0, 2);
        hud.attachChild(endMenu.node);
        layoutCountdown();

        pauseLayer.detachAllChildren();
        var dim = new Geometry("Dim", new Quad(width, height));
        var material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(0, 0, 0, .6f));
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        dim.setMaterial(material);
        pauseLayer.attachChild(dim);
        var title = Ui.banner("PAUSED", Math.round(58 * s), Ui.awt(185, 255, 240, 255), Ui.awt(25, 165, 170, 255));
        var pauseTitle = Ui.picture(assets, title, (width - title.getImage().getWidth()) / 2f, height * .8f - title.getImage().getHeight());
        pauseTitle.setLocalTranslation(pauseTitle.getLocalTranslation().x, pauseTitle.getLocalTranslation().y, 1);
        pauseLayer.attachChild(pauseTitle);
        String reason = pauseReason == null ? "" : pauseReason.getText();
        pauseReason = Ui.text(assets, camera, 20, ColorRGBA.White);
        pauseReason.setLocalTranslation(0, height * .8f - title.getImage().getHeight() - 14 * s, 1);
        pauseLayer.attachChild(pauseReason);
        pauseMenu.layout(assets, camera, width / 2f, height * .45f);
        pauseMenu.node.setLocalTranslation(0, 0, 1);
        pauseLayer.attachChild(pauseMenu.node);
        if (paused) pause(reason);
    }

    private void layoutCountdown() {
        var assets = app.getAssetManager();
        float scale = Ui.scale(app.getCamera());
        countdown.detachAllChildren();
        countdownWord.detachAllChildren();
        float band = 230 * scale;
        countdownBand = new Geometry("Band", new Quad(width, band));
        var material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        countdownBand.setMaterial(material);
        countdownBand.setLocalTranslation(0, (height - band) / 2f, -1);
        countdown.attachChild(countdownBand);
        var label = Ui.banner("ROUND " + (match == null ? 1 : Math.max(1, match.rounds())), Math.round(30 * scale),
                new java.awt.Color(255, 255, 255), new java.awt.Color(190, 200, 225));
        countdownRound = new com.jme3.ui.Picture("Round");
        countdownRound.setTexture(assets, label, true);
        countdownRound.setWidth(label.getImage().getWidth());
        countdownRound.setHeight(label.getImage().getHeight());
        countdownRound.setPosition((width - label.getImage().getWidth()) / 2f, (height + band) / 2f - label.getImage().getHeight() * .55f);
        countdown.attachChild(countdownRound);
        countdownWord.attachChild(new com.jme3.ui.Picture("Word"));
        countdownWord.setLocalTranslation(width / 2f, height / 2f - 14 * scale, 0);
        countdown.attachChild(countdownWord);
        hud.attachChild(countdown);
    }

    @Override protected void onEnable() {
        if (scene != null) app.getRootNode().attachChild(scene.root);
        app.getGuiNode().attachChild(hud);
        app.getViewPort().setBackgroundColor(new ColorRGBA(
                (theme.background() >> 16 & 255) / 255f, (theme.background() >> 8 & 255) / 255f, (theme.background() & 255) / 255f, 1));
        app.getViewPort().getQueue().setGeometryComparator(Bucket.Transparent, new ArenaScene.LayerOrder());
        app.mixer().music(theme.music());
        width = 0;
    }

    @Override protected void onDisable() {
        if (scene != null) scene.root.removeFromParent();
        hud.removeFromParent();
        pauseLayer.removeFromParent();
        runner.pause();
        app.mixer().stopMusic(); // no loop survives the match
        app.getViewPort().setBackgroundColor(new ColorRGBA(.025f, .04f, .065f, 1));
        app.getViewPort().getQueue().setGeometryComparator(Bucket.Transparent, new com.jme3.renderer.queue.TransparentComparator());
        if (cameraHome != null) app.getCamera().setLocation(cameraHome);
    }

    @Override protected void cleanup(Application application) { }
}
