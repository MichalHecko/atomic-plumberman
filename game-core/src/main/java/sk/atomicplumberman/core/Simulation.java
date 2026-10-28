package sk.atomicplumberman.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One deterministic round. Positions are integers in 1/1024 cell, time is ticks at 60 Hz and
 * {@link #step} runs the phases of plan chapter 8.1 in fixed order. No clock, IO or engine types.
 */
public final class Simulation {
    public static final int TICKS_PER_SECOND = 60;
    public static final int SUB = 1024;
    public static final int MAX_PLAYERS = 10;
    static final long GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MOVE_DIVISOR = TICKS_PER_SECOND * 1000L;
    private static final long NEVER = Long.MAX_VALUE;
    private static final Comparator<Flame> EARLIEST =
            Comparator.comparingLong(Flame::start).thenComparingInt(Flame::bomb);

    /**
     * Bomb life cycle (plan 5.2). DETONATING is the {@code detonating} flag inside one tick, REMOVED means
     * no longer listed. Allowed moves: PLACED → ROLLING (kick), ROLLING → PLACED (stop, obstacle),
     * PLACED → CARRIED (grab), PLACED → AIRBORNE (punch), CARRIED → AIRBORNE (throw) or PLACED (carrier dies),
     * AIRBORNE → PLACED (landing). Only PLACED and ROLLING bombs are on the ground: they block, are hit by
     * flames and can detonate.
     */
    public enum BombState { PLACED, ROLLING, CARRIED, AIRBORNE }

    /** A disease effect; base stats are never changed, effects are applied when values are used. */
    public record Effect(Disease type, long start, long end, int source) { }

    public static final class Player {
        final int id;
        final int team;
        int x;
        int z;
        int speed;
        int capacity;
        int range;
        int active;
        int kills;
        int turnWait;
        long moveRemainder;
        Direction moving = Direction.NONE;
        Direction facing = Direction.DOWN;
        boolean alive = true;
        Flame hitBy;
        Flame killedBy;
        int abilities;
        final List<Effect> effects = new ArrayList<>();
        long stunnedUntil;
        Bomb carrying;
        boolean bombHeld;
        int lastCell;
        long warpReadyAt;

        Player(int id, int team, Cell start, RulesetDefinition rules) {
            this.id = id;
            this.team = team;
            x = start.x() * SUB + SUB / 2;
            z = start.z() * SUB + SUB / 2;
            speed = rules.speed();
            capacity = rules.startBombs();
            range = rules.startRange();
            abilities = rules.startAbilities();
            lastCell = -1;
        }

        public int id() { return id; }
        public int team() { return team; }
        public int x() { return x; }
        public int z() { return z; }
        public boolean alive() { return alive; }
        public int capacity() { return capacity; }
        public int range() { return range; }
        public int speed() { return speed; }
        public int kills() { return kills; }
        public Direction facing() { return facing; }
        public boolean has(Item ability) { return (abilities & ability.bit()) != 0; }
        public List<Effect> effects() { return Collections.unmodifiableList(effects); }
        public boolean sick(Disease type) { return effects.stream().anyMatch(e -> e.type() == type); }
        public long stunnedUntil() { return stunnedUntil; }
        public boolean carrying() { return carrying != null; }
    }

    public static final class Bomb {
        final int id;
        final int owner;
        final int range;
        final long placedTick;
        final boolean jelly;
        boolean trigger;
        long explodesAt;
        BombState state = BombState.PLACED;
        /** Centre on the ground, in 1/1024 cell; cell = centre / SUB like any placement. */
        int px;
        int pz;
        Direction rolling = Direction.NONE;
        int speed;
        /** Moved by a conveyor belt rather than a kick: follows the belt and stops where it ends. */
        boolean belted;
        boolean stopAtCell;
        int kicker = -1;
        long kickTick;
        int carrier = -1;
        long fuseLeft;
        int fromX;
        int fromZ;
        Direction flyDir = Direction.NONE;
        int flyCells;
        long flyStart;
        long flyEnd;
        int bounces;
        int passers;
        boolean detonating;

        Bomb(int id, int owner, int x, int z, int range, long explodesAt, long placedTick, boolean trigger, boolean jelly) {
            this.id = id;
            this.owner = owner;
            this.px = x * SUB + SUB / 2;
            this.pz = z * SUB + SUB / 2;
            this.range = range;
            this.explodesAt = explodesAt;
            this.placedTick = placedTick;
            this.trigger = trigger;
            this.jelly = jelly;
        }

        public int id() { return id; }
        public int owner() { return owner; }
        public BombState state() { return state; }
        /** Cell of the centre on the ground; for a flight, the cell it started from. */
        public int x() { return state == BombState.AIRBORNE ? fromX : px / SUB; }
        public int z() { return state == BombState.AIRBORNE ? fromZ : pz / SUB; }
        public int px() { return px; }
        public int pz() { return pz; }
        public long explodesAt() { return explodesAt; }
        public boolean trigger() { return trigger; }
        public boolean jelly() { return jelly; }
        public int carrier() { return carrier; }
        public Direction rolling() { return rolling; }
        public int speed() { return speed; }
        public Direction flyDirection() { return flyDir; }
        public int flyCells() { return flyCells; }
        public long flyStart() { return flyStart; }
        public long flyEnd() { return flyEnd; }
    }

    /** Dangerous cell during [start, end), keeping its origin for kill credit and debugging. */
    public record Flame(int x, int z, long start, long end, int bomb, int owner) { }

    final RulesetDefinition rules;
    final int width;
    final int height;
    final int half;
    final char[] blocks;
    final Bomb[] bombAt;
    final Item[] hidden;
    final Item[] items;
    /** Conveyor direction per cell (NONE elsewhere) and the partner cell of each teleport (-1 elsewhere). */
    final Direction[] belts;
    final int[] warps;
    /** Sudden death order: free and destructible cells, ring by ring from the border inward. */
    final int[] closing;
    int closed;
    final List<Player> players = new ArrayList<>();
    final List<Bomb> bombs = new ArrayList<>();
    // ponytail: linear scan; per-cell index if profiling shows flame lookups hot
    final List<Flame> flames = new ArrayList<>();
    final List<SimulationEvent> events = new ArrayList<>();
    final long[][] lastContact;
    final long seed;
    /** SplitMix64 state: fill and hidden items at construction, then Disease, Super Disease and Random pickups. */
    long rng;
    long tick;
    int nextBombId;
    boolean over;
    int winner = -1;

    /** Free-for-all: every player is their own team. */
    public Simulation(ArenaLayout arena, List<Cell> starts, RulesetDefinition rules, long seed) {
        this(arena, starts, java.util.stream.IntStream.range(0, starts.size()).boxed().toList(), rules, seed);
    }

    public Simulation(ArenaLayout arena, List<Cell> starts, List<Integer> teams, RulesetDefinition rules, long seed) {
        this.rules = Objects.requireNonNull(rules, "rules");
        width = arena.width();
        height = arena.height();
        half = rules.hitbox() / 2;
        blocks = String.join("", arena.rows()).toCharArray();
        bombAt = new Bomb[blocks.length];
        hidden = new Item[blocks.length];
        items = new Item[blocks.length];
        belts = new Direction[blocks.length];
        warps = new int[blocks.length];
        java.util.Arrays.fill(warps, -1);
        for (int i = 0; i < blocks.length; i++) {
            char c = blocks[i];
            belts[i] = switch (c) {
                case '<' -> Direction.LEFT;
                case '>' -> Direction.RIGHT;
                case '^' -> Direction.UP;
                case 'v' -> Direction.DOWN;
                default -> Direction.NONE;
            };
            if (c >= 'a' && c <= 'z' && c != 'v') {
                for (int j = 0; j < blocks.length; j++) {
                    if (j != i && blocks[j] == c) warps[i] = j;
                }
            }
        }
        for (int i = 0; i < blocks.length; i++) {
            if (belts[i] != Direction.NONE || warps[i] >= 0) blocks[i] = '.';
        }
        this.seed = seed;
        rng = seed;
        boolean pool = !arena.itemPool().isEmpty();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == '?') blocks[i] = random(100) < rules.fillPercent() ? 'B' : '.';
            if (!pool && blocks[i] == 'B' && random(100) < rules.itemPercent()) hidden[i] = randomItem(true);
        }
        if (pool) {
            // The arena's own list: crates in a seeded random order get its items; extra items are left out.
            var crates = new ArrayList<Integer>();
            for (int i = 0; i < blocks.length; i++) if (blocks[i] == 'B') crates.add(i);
            for (int i = crates.size() - 1; i > 0; i--) Collections.swap(crates, i, random(i + 1));
            for (int i = 0; i < Math.min(crates.size(), arena.itemPool().size()); i++) hidden[crates.get(i)] = arena.itemPool().get(i);
        }
        closing = rules.suddenDeathTicks() == 0 ? new int[0] : spiral();
        if (starts.size() < 2 || starts.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("starts: expected 2.." + MAX_PLAYERS + " players");
        }
        if (teams.size() != starts.size() || teams.stream().anyMatch(t -> t < 0 || t >= starts.size())) {
            throw new IllegalArgumentException("teams: expected one team id 0.." + (starts.size() - 1) + " per start");
        }
        for (int i = 0; i < starts.size(); i++) {
            Cell s = starts.get(i);
            if (s.x() < 0 || s.z() < 0 || s.x() >= width || s.z() >= arena.height() || arena.cell(s.x(), s.z()) != '.') {
                throw new IllegalArgumentException("starts[" + i + "]: expected a free cell");
            }
            if (starts.subList(0, i).contains(s)) throw new IllegalArgumentException("starts[" + i + "]: duplicate");
            players.add(new Player(i, teams.get(i), s, rules));
            players.getLast().lastCell = s.z() * width + s.x();
        }
        lastContact = new long[players.size()][players.size()];
        for (long[] row : lastContact) java.util.Arrays.fill(row, Long.MIN_VALUE / 2);
        for (Player p : players) {
            for (Item item : arena.startItems()) apply(p, item == Item.RANDOM ? randomItem(false) : item, 0);
        }
        events.clear(); // pickups at the start are not news
    }

    public long tick() { return tick; }
    public long seed() { return seed; }
    public boolean isOver() { return over; }
    /** Winning team of a finished round (the slot in free-for-all), -1 for a draw or a round still running. */
    public int winner() { return winner; }
    public int kills(int slot) { return players.get(slot).kills; }
    public RulesetDefinition rules() { return rules; }
    public int width() { return width; }
    public int height() { return height; }
    public char block(int x, int z) { return blocks[z * width + x]; }
    /** Conveyor direction of a cell, NONE if there is no belt. */
    public Direction belt(int x, int z) { return belts[z * width + x]; }
    /** The other end of a teleport, or null. */
    public Cell warpExit(int x, int z) {
        int exit = warps[z * width + x];
        return exit < 0 ? null : new Cell(exit % width, exit / width);
    }
    /** Visible pickup or null; hidden ones are not public. */
    public Item item(int x, int z) { return items[z * width + x]; }
    public List<Player> players() { return Collections.unmodifiableList(players); }
    public List<Bomb> bombs() { return Collections.unmodifiableList(bombs); }
    public List<Flame> flames() { return Collections.unmodifiableList(flames); }
    /** Events of the last {@link #step}. */
    public List<SimulationEvent> events() { return Collections.unmodifiableList(events); }

    public void step(List<PlayerInputFrame> inputs) {
        if (over) throw new IllegalStateException("round is over");
        if (inputs.size() != players.size()) {
            throw new IllegalArgumentException("inputs: expected " + players.size() + " frames");
        }
        long now = tick;
        events.clear();
        // 1. Inputs: stunned players do nothing; reversed controls flip the direction.
        var frames = new ArrayList<PlayerInputFrame>();
        for (Player p : players) {
            var f = Objects.requireNonNullElse(inputs.get(p.id), PlayerInputFrame.NEUTRAL);
            if (!p.alive || p.stunnedUntil > now) f = new PlayerInputFrame(Direction.NONE, false, f.bombHeld(), false);
            else if (p.sick(Disease.REVERSED)) f = new PlayerInputFrame(opposite(f.direction()), f.bomb(), f.bombHeld(), f.action());
            if (f.direction() != Direction.NONE) p.facing = f.direction();
            p.bombHeld = f.bombHeld();
            frames.add(f);
        }
        // 2. Timed effects end, hidden items appear, the arena closes.
        flames.removeIf(f -> f.end() <= now);
        revealItems();
        for (Player p : players) p.effects.removeIf(e -> e.end() <= now);
        while (closed < closing.length && closeTick(closed) <= now) close(closing[closed++], now);
        // 3. Movement: players (kicking on contact), then rolling bombs against the result.
        for (Player p : players) {
            if (p.alive) move(p, frames.get(p.id).direction(), now);
        }
        for (Player p : players) {
            if (p.alive) ride(p);
        }
        rollBombs();
        // 4. Landings and teleports.
        for (Bomb b : List.copyOf(bombs)) {
            if (b.state == BombState.AIRBORNE && b.flyEnd <= now) land(b, now);
        }
        for (Player p : players) {
            if (p.alive) warp(p, now);
        }
        for (Bomb b : bombs) {
            for (Player p : players) {
                if (!overlapsBomb(p, b)) b.passers &= ~(1 << p.id);
            }
        }
        // 5. Actions of players not already hit this tick.
        for (Player p : players) {
            if (p.alive && p.hitBy == null && p.stunnedUntil <= now) act(p, frames.get(p.id), now);
        }
        // 6.–7. Detonations and chain reactions against one snapshot.
        var destroyed = detonate(now);
        // 8. Deaths, carried bombs falling, blocks and detonated bombs removed.
        for (Player p : players) {
            if (!p.alive) continue;
            Flame hit = earliest(p.hitBy, flameOn(p));
            p.hitBy = null;
            if (hit != null) kill(p, hit, now);
        }
        for (int cell : destroyed) {
            if (blocks[cell] == '.') continue;
            blocks[cell] = '.';
            events.add(new SimulationEvent(SimulationEvent.Type.BLOCK_DESTROYED, -1, cell % width, cell / width));
        }
        for (Bomb b : List.copyOf(bombs)) {
            if (b.detonating) remove(b);
        }
        // 9. Survivors pick up items and pass diseases on contact; trigger bombs of the dead or of
        // players who lost the ability (stun, conflicting pickup) get a fuse.
        for (Player p : players) {
            if (p.alive) pickUp(p, now);
        }
        spreadDiseases(now);
        for (Bomb b : bombs) {
            Player owner = players.get(b.owner);
            if (b.trigger && (!owner.alive || !owner.has(Item.TRIGGER))) toTimed(b, now);
        }
        // 10. Round result after the whole tick.
        tick++;
        var aliveTeams = players.stream().filter(p -> p.alive).map(p -> p.team).distinct().toList();
        if (aliveTeams.size() <= 1 || tick >= rules.roundTicks()) {
            over = true;
            winner = aliveTeams.size() == 1 ? aliveTeams.getFirst() : -1;
        }
    }

    private static Direction opposite(Direction d) {
        return switch (d) {
            case UP -> Direction.DOWN;
            case DOWN -> Direction.UP;
            case LEFT -> Direction.RIGHT;
            case RIGHT -> Direction.LEFT;
            case NONE -> Direction.NONE;
        };
    }

    // ---------------------------------------------------------------- player movement

    private int speedOf(Player p) {
        if (p.sick(Disease.SLOW)) return rules.speed() / 2;
        if (p.sick(Disease.FAST)) return rules.maxSpeed();
        return p.speed;
    }

    private void move(Player p, Direction want, long now) {
        if (want == Direction.NONE) {
            p.moving = Direction.NONE;
            p.turnWait = 0;
            return;
        }
        p.moveRemainder += (long) speedOf(p) * SUB;
        int dist = (int) (p.moveRemainder / MOVE_DIVISOR);
        p.moveRemainder %= MOVE_DIVISOR;
        if (want.perpendicularTo(p.moving) && p.turnWait < rules.turnBufferTicks() && !canTurn(p, want)
                && tryMove(p, p.moving, dist)) {
            p.turnWait++; // turn pressed early: keep going until the corridor opens
        } else if (tryMove(p, want, dist)) {
            p.moving = want;
            p.turnWait = 0;
        } else {
            p.moving = Direction.NONE;
            p.turnWait = 0;
        }
        if (p.has(Item.KICK)) kick(p, want, now);
    }

    private boolean tryMove(Player p, Direction d, int dist) {
        boolean alongX = d.dx != 0;
        int sign = d.dx + d.dz;
        int moved = advance(p, alongX, sign, dist);
        if (moved == 0 && cornerAssist(p, d)) {
            int offset = (alongX ? p.z : p.x) % SUB - SUB / 2;
            int slide = advance(p, !alongX, offset > 0 ? -1 : 1, Math.min(dist, overhang(p, alongX)));
            p.hitBy = earliest(p.hitBy, flameOn(p));
            moved = slide + advance(p, alongX, sign, dist - slide);
        }
        if (moved > 0) p.hitBy = earliest(p.hitBy, flameOn(p));
        return moved > 0;
    }

    /** A turn is open when the next line of cells is free or the corner assist can slide into it. */
    private boolean canTurn(Player p, Direction d) {
        boolean alongX = d.dx != 0;
        int ahead = ahead(p, d);
        int across = alongX ? p.z : p.x;
        for (int a = (across - half) / SUB; a <= (across + half - 1) / SUB; a++) {
            if (blocked(p, alongX ? ahead : a, alongX ? a : ahead)) return cornerAssist(p, d);
        }
        return true;
    }

    /** The hitbox overhangs its lane by at most the assist distance and the lane continues ahead. */
    private boolean cornerAssist(Player p, Direction d) {
        boolean alongX = d.dx != 0;
        int overhang = overhang(p, alongX);
        int lane = (alongX ? p.z : p.x) / SUB;
        int ahead = ahead(p, d);
        return overhang > 0 && overhang <= rules.cornerAssist() && !blocked(p, alongX ? ahead : lane, alongX ? lane : ahead);
    }

    private int overhang(Player p, boolean alongX) {
        return Math.abs((alongX ? p.z : p.x) % SUB - SUB / 2) - (SUB / 2 - half);
    }

    /** Cell index just beyond the hitbox's leading edge. */
    private int ahead(Player p, Direction d) {
        int sign = d.dx + d.dz;
        int lead = d.dx != 0 ? p.x : p.z;
        return (sign > 0 ? lead + half - 1 : lead - half) / SUB + sign;
    }

    /** Moves along one axis, stopping flush at a blocked cell. Positions stay positive inside the border. */
    private int advance(Player p, boolean alongX, int sign, int dist) {
        int along = alongX ? p.x : p.z;
        int across = alongX ? p.z : p.x;
        int lead = sign > 0 ? along + half - 1 : along - half;
        int target = lead + sign * dist;
        int toCell = target / SUB;
        if (toCell != lead / SUB) { // dist < hitbox < SUB, so at most one new line of cells
            for (int a = (across - half) / SUB; a <= (across + half - 1) / SUB; a++) {
                if (blocked(p, alongX ? toCell : a, alongX ? a : toCell)) {
                    target = sign > 0 ? toCell * SUB - 1 : (toCell + 1) * SUB;
                    break;
                }
            }
        }
        int moved = Math.abs(target - lead);
        if (alongX) p.x += sign * moved;
        else p.z += sign * moved;
        return moved;
    }

    private boolean blocked(Player p, int x, int z) {
        Bomb bomb = bombAt[z * width + x];
        return blocks[z * width + x] != '.' || bomb != null && (bomb.passers & 1 << p.id) == 0;
    }

    private boolean overlaps(Player p, int x, int z) {
        return (p.x - half) / SUB <= x && x <= (p.x + half - 1) / SUB
                && (p.z - half) / SUB <= z && z <= (p.z + half - 1) / SUB;
    }

    private boolean overlapsBomb(Player p, Bomb b) {
        for (int cell : cells(b)) {
            if (overlaps(p, cell % width, cell / width)) return true;
        }
        return false;
    }

    private boolean playerOn(int cell) {
        for (Player p : players) {
            if (p.alive && overlaps(p, cell % width, cell / width)) return true;
        }
        return false;
    }

    private Flame flameOn(Player p) {
        Flame best = null;
        for (Flame f : flames) {
            if (overlaps(p, f.x(), f.z()) && harms(f, p)) best = earliest(best, f);
        }
        return best;
    }

    /** With team damage off, flames still burn the owner and enemies but spare the owner's teammates. */
    private boolean harms(Flame f, Player p) {
        return rules.teamDamage() || f.owner() == p.id || players.get(f.owner()).team != p.team;
    }

    private boolean burning(int cell) {
        for (Flame f : flames) {
            if (f.z() * width + f.x() == cell) return true;
        }
        return false;
    }

    private static Flame earliest(Flame a, Flame b) {
        if (a == null) return b;
        if (b == null) return a;
        return EARLIEST.compare(a, b) <= 0 ? a : b;
    }

    // ---------------------------------------------------------------- bombs on the ground

    /** Cells covered by a one-cell footprint around the centre: one when centred, two while rolling across. */
    private List<Integer> cells(Bomb b) {
        int x0 = (b.px - SUB / 2) / SUB;
        int x1 = (b.px + SUB / 2 - 1) / SUB;
        int z0 = (b.pz - SUB / 2) / SUB;
        int z1 = (b.pz + SUB / 2 - 1) / SUB;
        var cells = new ArrayList<Integer>(2);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) cells.add(z * width + x);
        }
        return cells;
    }

    private void index(Bomb b) {
        for (int cell : cells(b)) bombAt[cell] = b;
    }

    private void unindex(Bomb b) {
        for (int cell : cells(b)) {
            if (bombAt[cell] == b) bombAt[cell] = null;
        }
    }

    private boolean centred(Bomb b) {
        return b.px % SUB == SUB / 2 && b.pz % SUB == SUB / 2;
    }

    /** A rolling bomb may enter a cell without blocks, ground bombs or living players. */
    private boolean rollable(int cell) {
        return blocks[cell] == '.' && bombAt[cell] == null && !playerOn(cell);
    }

    /** A belt under the player's centre pushes them on, through the same collision as walking. */
    private void ride(Player p) {
        Direction belt = belts[p.z / SUB * width + p.x / SUB];
        if (belt == Direction.NONE) return;
        if (advance(p, belt.dx != 0, belt.dx + belt.dz, rules.beltSpeed()) > 0) p.hitBy = earliest(p.hitBy, flameOn(p));
    }

    /**
     * A teleport fires when a player's centre enters it and their cooldown has passed; an exit with a
     * block, bomb or other player cancels it. Arriving does not count as entering, so nobody bounces
     * back and forth, and fire at the exit hits in the same tick.
     */
    private void warp(Player p, long now) {
        int cell = p.z / SUB * width + p.x / SUB;
        int exit = warps[cell];
        if (exit >= 0 && cell != p.lastCell && now >= p.warpReadyAt) {
            boolean taken = blocks[exit] != '.' || bombAt[exit] != null;
            for (Player q : players) taken |= q != p && q.alive && overlaps(q, exit % width, exit / width);
            if (!taken) {
                p.x = exit % width * SUB + SUB / 2;
                p.z = exit / width * SUB + SUB / 2;
                p.warpReadyAt = now + rules.warpCooldown();
                cell = exit;
                p.hitBy = earliest(p.hitBy, flameOn(p));
                events.add(new SimulationEvent(SimulationEvent.Type.PLAYER_WARPED, p.id, exit % width, exit / width));
            }
        }
        p.lastCell = cell;
    }

    private void kick(Player p, Direction d, long now) {
        if (d == Direction.NONE) return;
        int sign = d.dx + d.dz;
        int lead = d.dx != 0 ? (sign > 0 ? p.x + half : p.x - half) : (sign > 0 ? p.z + half : p.z - half);
        if (lead % SUB != 0) return; // only when the hitbox touches the next cell
        int lane = (d.dx != 0 ? p.z : p.x) / SUB;
        int ahead = ahead(p, d);
        int cell = d.dx != 0 ? lane * width + ahead : ahead * width + lane;
        Bomb b = bombAt[cell];
        if (b == null || b.state != BombState.PLACED || (b.passers & 1 << p.id) != 0) return;
        if (!rollable(cell + d.dz * width + d.dx)) return; // blocked kick: the bomb stays
        b.state = BombState.ROLLING;
        b.rolling = d;
        b.speed = rules.rollSpeed();
        b.belted = false;
        b.stopAtCell = false;
        b.kicker = p.id;
        b.kickTick = now;
        events.add(new SimulationEvent(SimulationEvent.Type.BOMB_KICKED, p.id, cell % width, cell / width));
    }

    /**
     * Rolling bombs decide at cell centres. All decisions use the state before any of them moves, and two
     * bombs wanting the same cell both stop, so head-on bombs never pass through each other.
     */
    private void rollBombs() {
        // Belts steer the bombs they carry from each cell centre; a belted bomb stops where the belt ends.
        for (Bomb b : bombs) {
            if (!onGround(b) || !centred(b)) continue;
            Direction belt = belts[b.pz / SUB * width + b.px / SUB];
            if (b.state == BombState.PLACED && belt != Direction.NONE) {
                b.state = BombState.ROLLING;
                b.belted = true;
                b.speed = rules.beltSpeed();
            }
            if (b.state == BombState.ROLLING && b.belted) {
                b.rolling = belt;
                if (belt == Direction.NONE) {
                    b.state = BombState.PLACED;
                    b.belted = false;
                }
            }
        }
        var rolling = bombs.stream().filter(b -> b.state == BombState.ROLLING).toList();
        var targets = new java.util.HashMap<Integer, Integer>();
        for (Bomb b : rolling) {
            if (centred(b) && !b.stopAtCell) targets.merge(next(b, b.rolling), 1, Integer::sum);
        }
        // Decide every centred bomb first, then move: a cell vacated this tick is not entered this tick.
        var moving = new ArrayList<Bomb>();
        for (Bomb b : rolling) {
            if (!centred(b)) {
                moving.add(b);
                continue;
            }
            int target = next(b, b.rolling);
            if (!b.stopAtCell && rollable(target) && targets.get(target) == 1) {
                moving.add(b);
                continue;
            }
            Direction back = opposite(b.rolling);
            if (b.jelly && !b.belted && !b.stopAtCell && rollable(next(b, back)) && !targets.containsKey(next(b, back))) {
                b.rolling = back; // jelly bounces; it moves on from the next tick
            } else {
                b.state = BombState.PLACED; // a belted bomb waits here and tries again next tick
                b.rolling = Direction.NONE;
                b.belted = false;
            }
        }
        for (Bomb b : moving) {
            if (centred(b)) bombAt[next(b, b.rolling)] = b;
            b.px += b.rolling.dx * b.speed;
            b.pz += b.rolling.dz * b.speed;
            if (centred(b)) bombAt[next(b, opposite(b.rolling))] = null;
        }
    }

    private int next(Bomb b, Direction d) {
        return (b.pz / SUB + d.dz) * width + b.px / SUB + d.dx;
    }

    // ---------------------------------------------------------------- flights

    private int wrap(int value, int size) {
        return value < 1 ? size - 2 : value > size - 2 ? 1 : value;
    }

    /** Starts a flight of {@code cells} cells from a cell; leaving the inner arena wraps to the other side. */
    private void launch(Bomb b, int x, int z, Direction d, int cells, long now, Player actor, SimulationEvent.Type how) {
        b.state = BombState.AIRBORNE;
        b.rolling = Direction.NONE;
        b.fromX = x;
        b.fromZ = z;
        b.flyDir = d;
        b.flyCells = cells;
        b.flyStart = now;
        b.flyEnd = now + (long) cells * rules.flightTicksPerCell();
        b.passers = 0;
        events.add(new SimulationEvent(how, actor.id, x, z));
    }

    /**
     * A landing cell must be free of blocks and ground bombs. Players there are stunned and lose a bonus;
     * the bomb bounces one cell further. Landing in fire detonates at once; a fuse that ran out in the
     * air detonates on landing. Past the bounce limit the bomb is removed and reported.
     */
    private void land(Bomb b, long now) {
        int x = b.fromX;
        int z = b.fromZ;
        for (int i = 0; i < b.flyCells; i++) {
            x = wrap(x + b.flyDir.dx, width);
            z = wrap(z + b.flyDir.dz, height);
        }
        int cell = z * width + x;
        boolean hitPlayer = false;
        for (Player p : players) {
            if (!p.alive || !overlaps(p, x, z)) continue;
            hitPlayer = true;
            p.stunnedUntil = now + rules.stunTicks();
            events.add(new SimulationEvent(SimulationEvent.Type.PLAYER_STUNNED, p.id, x, z));
            dropBonus(p);
        }
        if (hitPlayer || blocks[cell] != '.' || bombAt[cell] != null) {
            if (++b.bounces > 2 * (width + height)) {
                events.add(new SimulationEvent(SimulationEvent.Type.BOMB_LOST, b.owner, x, z));
                remove(b);
                return;
            }
            b.fromX = x;
            b.fromZ = z;
            b.flyCells = 1;
            b.flyStart = now;
            b.flyEnd = now + rules.flightTicksPerCell();
            return;
        }
        b.state = BombState.PLACED;
        b.flyDir = Direction.NONE;
        b.bounces = 0;
        b.px = x * SUB + SUB / 2;
        b.pz = z * SUB + SUB / 2;
        index(b);
        if (burning(cell)) b.explodesAt = Math.min(b.explodesAt, now);
        events.add(new SimulationEvent(SimulationEvent.Type.BOMB_LANDED, b.owner, x, z));
    }

    // ---------------------------------------------------------------- actions

    private void act(Player p, PlayerInputFrame f, long now) {
        int cell = p.z / SUB * width + p.x / SUB;
        if (p.carrying != null && !f.bombHeld()) {
            Bomb b = p.carrying;
            p.carrying = null;
            b.carrier = -1;
            b.explodesAt = b.trigger ? NEVER : now + b.fuseLeft;
            launch(b, cell % width, cell / width, p.facing, rules.throwCells(), now, p, SimulationEvent.Type.BOMB_THROWN);
        }
        if (f.action()) action(p, cell, now);
        if (f.bomb() && p.carrying == null) {
            Bomb own = bombAt[cell];
            if (own != null && own.owner == p.id && own.state == BombState.PLACED && centred(own)) {
                if (p.has(Item.GRAB)) {
                    unindex(own);
                    own.state = BombState.CARRIED;
                    own.carrier = p.id;
                    own.fuseLeft = own.trigger ? NEVER : Math.max(1, own.explodesAt - now);
                    own.explodesAt = NEVER; // the manual: a held bomb does not tick
                    p.carrying = own;
                } else if (p.has(Item.SPOOGE) && now - own.placedTick <= rules.spoogeWindow()) {
                    for (int i = 1; p.active < p.capacity; i++) {
                        int x = own.px / SUB + p.facing.dx * i;
                        int z = own.pz / SUB + p.facing.dz * i;
                        if (blocks[z * width + x] != '.' || bombAt[z * width + x] != null) break;
                        placeBomb(p, x, z, now);
                    }
                }
            } else {
                placeBomb(p, cell % width, cell / width, now);
            }
        }
        if (p.sick(Disease.FORCED_BOMBS) && p.carrying == null && bombAt[cell] == null) placeBomb(p, cell % width, cell / width, now);
    }

    /** One Action per tick, by priority: trigger a bomb, punch the bomb ahead, stop the last kicked bomb. */
    private void action(Player p, int cell, long now) {
        if (p.has(Item.TRIGGER)) {
            for (Bomb b : bombs) { // ascending ID: the oldest first
                if (b.owner == p.id && b.trigger && onGround(b)) {
                    b.explodesAt = now;
                    return;
                }
            }
        }
        if (p.has(Item.PUNCH)) {
            int target = cell + p.facing.dz * width + p.facing.dx;
            Bomb b = bombAt[target];
            if (b != null && b.state == BombState.PLACED && centred(b)) {
                unindex(b);
                launch(b, target % width, target / width, p.facing, rules.throwCells(), now, p, SimulationEvent.Type.BOMB_PUNCHED);
                return;
            }
        }
        Bomb latest = null;
        for (Bomb b : bombs) {
            if (b.state == BombState.ROLLING && b.kicker == p.id && (latest == null || b.kickTick >= latest.kickTick)) latest = b;
        }
        if (latest != null) latest.stopAtCell = true;
    }

    private static boolean onGround(Bomb b) {
        return b.state == BombState.PLACED || b.state == BombState.ROLLING;
    }

    /** Placement locks in range and fuse (diseases included) at this moment, plan 4.3 and 5.3. */
    private void placeBomb(Player p, int x, int z, long now) {
        if (p.active >= p.capacity || bombAt[z * width + x] != null || p.sick(Disease.NO_BOMBS)) return;
        int range = p.sick(Disease.SHORT_RANGE) ? 1 : p.has(Item.GOLDFLAME) ? Math.max(width, height) : p.range;
        boolean trigger = p.has(Item.TRIGGER);
        long fuse = trigger ? NEVER : now + (p.sick(Disease.SHORT_FUSE) ? rules.shortFuseTicks() : rules.fuseTicks());
        var bomb = new Bomb(nextBombId++, p.id, x, z, range, fuse, now, trigger, p.has(Item.JELLY));
        for (Player q : players) {
            if (q.alive && overlaps(q, x, z)) bomb.passers |= 1 << q.id;
        }
        bombs.add(bomb);
        bombAt[z * width + x] = bomb;
        p.active++;
        events.add(new SimulationEvent(SimulationEvent.Type.BOMB_PLACED, p.id, x, z));
    }

    /** Trigger bombs whose owner died or lost the ability get a finite fuse, plan 4.3. */
    private void toTimed(Bomb b, long now) {
        b.trigger = false;
        if (b.state == BombState.CARRIED) b.fuseLeft = Math.min(b.fuseLeft, rules.triggerFallbackTicks());
        else b.explodesAt = Math.min(b.explodesAt, now + rules.triggerFallbackTicks());
    }

    private void remove(Bomb b) {
        if (onGround(b)) unindex(b);
        if (b.carrier >= 0) players.get(b.carrier).carrying = null;
        bombs.remove(b);
        players.get(b.owner).active--;
    }

    // ---------------------------------------------------------------- explosions

    /**
     * Resolves expired bombs, bombs in fire and chain reactions against one snapshot: blocks and bombs stay in
     * place until the whole queue is done, so the result does not depend on iteration order.
     */
    private List<Integer> detonate(long now) {
        var queue = new ArrayDeque<Bomb>();
        for (Bomb b : bombs) {
            if (onGround(b) && (b.explodesAt <= now || cells(b).stream().anyMatch(this::burning))) {
                b.detonating = true;
                queue.add(b);
            }
        }
        var destroyed = new ArrayList<Integer>();
        while (!queue.isEmpty()) {
            Bomb b = queue.poll();
            int bx = b.px / SUB;
            int bz = b.pz / SUB;
            events.add(new SimulationEvent(SimulationEvent.Type.EXPLOSION, b.owner, bx, bz));
            burn(bx, bz, b, now);
            for (Direction d : Direction.MOVES) {
                for (int i = 1; i <= b.range; i++) {
                    int x = bx + d.dx * i;
                    int z = bz + d.dz * i;
                    if (x < 0 || z < 0 || x >= width || z >= height) break;
                    char block = blocks[z * width + x];
                    if (block == '#') break;
                    burn(x, z, b, now);
                    if (block == 'B') {
                        destroyed.add(z * width + x);
                        break;
                    }
                    Bomb hit = bombAt[z * width + x];
                    if (hit != null) {
                        if (!hit.detonating) {
                            hit.detonating = true;
                            queue.add(hit);
                        }
                        break;
                    }
                }
            }
        }
        return destroyed;
    }

    /** Flame over a visible pickup destroys it; rays do not depend on items, so this is order-free. */
    private void burn(int x, int z, Bomb b, long now) {
        flames.add(new Flame(x, z, now, now + rules.flameTicks(), b.id, b.owner));
        items[z * width + x] = null;
    }

    private void kill(Player p, Flame flame, long now) {
        events.add(new SimulationEvent(SimulationEvent.Type.PLAYER_DIED, p.id, p.x / SUB, p.z / SUB));
        p.alive = false;
        p.killedBy = flame;
        Player owner = players.get(flame.owner());
        owner.kills += owner.team == p.team ? -1 : 1; // suicide and team kills cost a point
        dropCarried(p, now);
    }

    /** A dead carrier drops the bomb into their cell; if that cell cannot hold it, the bomb is gone. */
    private void dropCarried(Player p, long now) {
        Bomb b = p.carrying;
        if (b == null) return;
        p.carrying = null;
        b.carrier = -1;
        int cell = p.z / SUB * width + p.x / SUB;
        if (blocks[cell] != '.' || bombAt[cell] != null) {
            events.add(new SimulationEvent(SimulationEvent.Type.BOMB_LOST, b.owner, cell % width, cell / width));
            bombs.remove(b);
            players.get(b.owner).active--;
            return;
        }
        b.state = BombState.PLACED;
        b.px = cell % width * SUB + SUB / 2;
        b.pz = cell / width * SUB + SUB / 2;
        b.explodesAt = b.trigger ? NEVER : now + b.fuseLeft;
        for (Player q : players) {
            if (q.alive && overlaps(q, cell % width, cell / width)) b.passers |= 1 << q.id;
        }
        index(b);
    }

    // ---------------------------------------------------------------- items and diseases

    /** A hidden pickup appears once its block is gone and every flame on the cell has ended. */
    private void revealItems() {
        var lit = new boolean[blocks.length];
        for (Flame f : flames) lit[f.z() * width + f.x()] = true;
        for (int i = 0; i < blocks.length; i++) {
            if (hidden[i] != null && blocks[i] == '.' && !lit[i]) {
                items[i] = hidden[i];
                hidden[i] = null;
            }
        }
    }

    private Item randomItem(boolean allowRandom) {
        var weights = rules.itemWeights();
        int total = 0;
        for (Item item : Item.values()) total += allowRandom || item != Item.RANDOM ? weights.get(item.ordinal()) : 0;
        int pick = random(total);
        for (Item item : Item.values()) {
            if (!allowRandom && item == Item.RANDOM) continue;
            pick -= weights.get(item.ordinal());
            if (pick < 0) return item;
        }
        throw new IllegalStateException("unreachable: weights are validated");
    }

    private void pickUp(Player p, long now) {
        int cell = p.z / SUB * width + p.x / SUB;
        Item item = items[cell];
        if (item == null) return;
        items[cell] = null;
        events.add(new SimulationEvent(SimulationEvent.Type.ITEM_PICKED, p.id, cell % width, cell / width));
        apply(p, item == Item.RANDOM ? randomItem(false) : item, now);
    }

    private void apply(Player p, Item item, long now) {
        switch (item) {
            case BOMB_UP -> p.capacity = Math.min(p.capacity + 1, rules.maxBombs());
            case FLAME -> p.range = Math.min(p.range + 1, rules.maxRange());
            case SKATE -> p.speed = Math.min(p.speed + rules.speedStep(), rules.maxSpeed());
            case DISEASE -> infect(p, Disease.values()[random(Disease.values().length)], -1, now);
            case SUPER_DISEASE -> {
                // Up to three different effects; an opposite of one already drawn is skipped.
                var drawn = new LinkedHashSet<Disease>();
                for (int attempt = 0; attempt < 10 && drawn.size() < 3; attempt++) {
                    var type = Disease.values()[random(Disease.values().length)];
                    if (!drawn.contains(type.opposite())) drawn.add(type);
                }
                drawn.forEach(type -> infect(p, type, -1, now));
            }
            case RANDOM -> throw new IllegalStateException("RANDOM is resolved on pickup");
            default -> {
                for (Item other : Item.values()) {
                    if (item.conflictsWith(other) && p.has(other)) {
                        p.abilities &= ~other.bit();
                        dropItem(other, p);
                    }
                }
                p.abilities |= item.bit();
            }
        }
        if (item != Item.DISEASE && item != Item.SUPER_DISEASE && !p.effects.isEmpty()) {
            p.effects.removeFirst(); // a good pickup cures the oldest effect
        }
    }

    void infect(Player p, Disease type, int source, long now) {
        p.effects.removeIf(e -> e.type() == type || e.type() == type.opposite());
        p.effects.add(new Effect(type, now, now + rules.diseaseTicks(), source));
        events.add(new SimulationEvent(SimulationEvent.Type.PLAYER_INFECTED, p.id, p.x / SUB, p.z / SUB));
    }

    /** Touching players hand over their newest effect the other lacks; each pair then waits the cooldown. */
    private void spreadDiseases(long now) {
        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                Player a = players.get(i);
                Player b = players.get(j);
                if (!a.alive || !b.alive || now - lastContact[i][j] < rules.contactCooldown()) continue;
                if (Math.abs(a.x - b.x) >= 2 * half || Math.abs(a.z - b.z) >= 2 * half) continue;
                Effect fromA = a.effects.isEmpty() ? null : a.effects.getLast();
                Effect fromB = b.effects.isEmpty() ? null : b.effects.getLast();
                boolean passed = false;
                if (fromA != null && !b.sick(fromA.type())) {
                    infect(b, fromA.type(), a.id, now);
                    passed = true;
                }
                if (fromB != null && !a.sick(fromB.type())) {
                    infect(a, fromB.type(), b.id, now);
                    passed = true;
                }
                if (passed) lastContact[i][j] = now;
            }
        }
    }

    /** A stunned player loses one bonus: basic upgrades first, then abilities in item order. */
    private void dropBonus(Player p) {
        Item lost = null;
        if (p.capacity > rules.startBombs()) {
            p.capacity--;
            lost = Item.BOMB_UP;
        } else if (p.range > rules.startRange()) {
            p.range--;
            lost = Item.FLAME;
        } else if (p.speed > rules.speed()) {
            p.speed -= rules.speedStep();
            lost = Item.SKATE;
        } else {
            for (Item item : Item.values()) {
                if (item.ability() && p.has(item)) {
                    p.abilities &= ~item.bit();
                    lost = item;
                    break;
                }
            }
        }
        if (lost != null) dropItem(lost, p);
    }

    /**
     * Puts an item on the nearest safe cell by Manhattan distance, scanning rows top to bottom and cells
     * left to right; cells with players are skipped so it is not picked up again at once. No cell: it vanishes.
     */
    private void dropItem(Item item, Player p) {
        int cx = p.x / SUB;
        int cz = p.z / SUB;
        for (int r = 1; r < width + height; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (x < 0 || z < 0 || x >= width || z >= height) continue;
                    int cell = z * width + x;
                    if (blocks[cell] != '.' || items[cell] != null || bombAt[cell] != null || burning(cell) || playerOn(cell)) continue;
                    items[cell] = item;
                    events.add(new SimulationEvent(SimulationEvent.Type.ITEM_DROPPED, p.id, x, z));
                    return;
                }
            }
        }
    }

    private int random(int bound) {
        long value = splitMix(rng);
        rng += GAMMA;
        return (int) Long.remainderUnsigned(value, bound);
    }

    // ---------------------------------------------------------------- sudden death

    private int[] spiral() {
        var order = new LinkedHashSet<Integer>();
        for (int r = 0; 1 + r <= width - 2 - r && 1 + r <= height - 2 - r; r++) {
            int left = 1 + r, top = 1 + r, right = width - 2 - r, bottom = height - 2 - r;
            for (int x = left; x <= right; x++) order.add(top * width + x);
            for (int z = top; z <= bottom; z++) order.add(z * width + right);
            for (int x = right; x >= left; x--) order.add(bottom * width + x);
            for (int z = bottom; z >= top; z--) order.add(z * width + left);
        }
        return order.stream().filter(cell -> blocks[cell] != '#').mapToInt(Integer::intValue).toArray();
    }

    private long closeTick(int index) {
        return rules.roundTicks() - rules.suddenDeathTicks() + rules.suddenDeathWarning() + (long) index * rules.suddenDeathStep();
    }

    /**
     * A closing cell becomes solid before this tick's explosions are resolved, so flames stop at it.
     * Its pickup and ground bomb vanish (capacity returns) and anyone on it is eliminated without a killer;
     * a bomb they carried falls into the closed cell and vanishes too.
     */
    private void close(int cell, long now) {
        int x = cell % width;
        int z = cell / width;
        blocks[cell] = '#';
        hidden[cell] = null;
        items[cell] = null;
        flames.removeIf(f -> f.x() == x && f.z() == z);
        Bomb bomb = bombAt[cell];
        if (bomb != null) remove(bomb);
        events.add(new SimulationEvent(SimulationEvent.Type.CELL_CLOSED, -1, x, z));
        for (Player p : players) {
            if (!p.alive || !overlaps(p, x, z)) continue;
            p.alive = false;
            p.killedBy = null;
            events.add(new SimulationEvent(SimulationEvent.Type.PLAYER_DIED, p.id, p.x / SUB, p.z / SUB));
            dropCarried(p, now);
        }
    }

    /** Cells announced for closing within the warning window, in closing order. */
    public List<Cell> closingSoon() {
        var cells = new ArrayList<Cell>();
        for (int i = closed; i < closing.length && closeTick(i) - tick <= rules.suddenDeathWarning(); i++) {
            cells.add(new Cell(closing[i] % width, closing[i] / width));
        }
        return cells;
    }

    // ---------------------------------------------------------------- hashing

    /** Canonical hash of everything that affects future ticks; compare it between processes. */
    public long stateHash() {
        long h = mix(0, rng, tick, nextBombId, over ? 1 : 0, winner, closed);
        for (int i = 0; i < blocks.length; i++) {
            h = mix(h, blocks[i], hidden[i] == null ? -1 : hidden[i].ordinal(), items[i] == null ? -1 : items[i].ordinal());
        }
        for (Player p : players) {
            h = mix(h, p.x, p.z, p.team, p.speed, p.capacity, p.range, p.active, p.kills, p.turnWait, p.moveRemainder,
                    p.moving.ordinal(), p.facing.ordinal(), p.alive ? 1 : 0, p.killedBy == null ? -1 : p.killedBy.bomb(),
                    p.abilities, p.stunnedUntil, p.carrying == null ? -1 : p.carrying.id, p.bombHeld ? 1 : 0,
                    p.lastCell, p.warpReadyAt);
            for (Effect e : p.effects) h = mix(h, e.type().ordinal(), e.start(), e.end(), e.source());
        }
        for (long[] row : lastContact) h = mix(h, row);
        for (Bomb b : bombs) {
            h = mix(h, b.id, b.owner, b.px, b.pz, b.range, b.explodesAt, b.passers, b.state.ordinal(), b.trigger ? 1 : 0,
                    b.jelly ? 1 : 0, b.rolling.ordinal(), b.speed, b.belted ? 1 : 0, b.stopAtCell ? 1 : 0, b.kicker, b.kickTick, b.carrier, b.fuseLeft,
                    b.fromX, b.fromZ, b.flyDir.ordinal(), b.flyCells, b.flyStart, b.flyEnd, b.bounces);
        }
        for (Flame f : flames) h = mix(h, f.x(), f.z(), f.start(), f.end(), f.bomb(), f.owner());
        return h;
    }

    private static long mix(long h, long... values) {
        for (long v : values) h = splitMix(h ^ v);
        return h;
    }

    static long splitMix(long z) {
        z += GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
