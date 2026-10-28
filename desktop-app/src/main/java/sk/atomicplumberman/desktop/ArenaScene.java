package sk.atomicplumberman.desktop;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.GeometryComparator;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import sk.atomicplumberman.content.ThemeDefinition;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.Item;
import sk.atomicplumberman.core.Simulation;
import sk.atomicplumberman.core.SimulationEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Top-down sprite view of one round, drawn like Atomic Bomberman and Bombman: tile textures, the
 * wavy map frame, recoloured robots about two cells tall standing in their cell, bombs in the owner's
 * colour. Sprites are Bombman's by Miloslav Číž (GPL-3.0-or-later, docs/CREDITS.md). Reads the
 * simulation, never changes it. Cell (x, z) is centred at world (x, 0, z × {@link #ROW}); one sprite
 * pixel is {@link #PX} world units, so a 50 × 45 px tile is one cell. Height (y) only orders layers.
 */
final class ArenaScene {
    /** Bombman's slot colours, in the order of the original game. */
    static final ColorRGBA[] PLAYER_COLORS = {
            rgb(210, 210, 210), rgb(10, 10, 10), rgb(255, 0, 0), rgb(0, 0, 255), rgb(0, 255, 0),
            rgb(52, 237, 250), rgb(255, 255, 69), rgb(255, 192, 74), rgb(168, 127, 56), rgb(209, 117, 206)};
    static final ColorRGBA[] TEAM_COLORS = {new ColorRGBA(1, .6f, .1f, 1), new ColorRGBA(.3f, .8f, 1, 1)};
    static final float PX = .02f;
    static final float ROW = .9f;
    private static final String SPRITES = "Textures/Bombman/";
    private static final float FLOOR = 0, DECOR = .01f, TILE = .02f, ITEM = .03f, FLAME = .04f, SHADOW = .05f, ACTOR = .1f,
            FLYING = .5f, EFFECT = .6f;
    private static final float VANISH_SECONDS = .25f;
    private static final float DEATH_SECONDS = .7f;
    private static final float EXPLOSION_SECONDS = .5f;
    /** Anchors in sprite pixels: a player's feet (Bombman's, also for the 54 × 85 px dying frames) and the bomb ball's centre, on the cell centre. */
    private static final float PLAYER_X = 30, PLAYER_Y = 80, DYING_X = 27, DYING_Y = 76, BOMB_X = 22, BOMB_Y = 21;

    final Node root = new Node("Arena");
    private final AssetManager assets;
    private final BitmapFont font;
    private final Simulation sim;
    private final ThemeDefinition theme;
    private final boolean flicker;
    private final boolean[] solidAtStart;
    private final Map<String, Texture2D> textures = new HashMap<>();
    private final Map<String, Material> materials = new HashMap<>();
    private final Map<Integer, Spatial> blocks = new HashMap<>();
    private final Map<Integer, Spatial> bombs = new HashMap<>();
    private final Map<Integer, Spatial> flames = new HashMap<>();
    private final Map<Integer, Spatial> items = new HashMap<>();
    private final Map<Integer, Spatial> closed = new HashMap<>();
    private final Map<Integer, Spatial> warnings = new HashMap<>();
    private final List<Node> belts = new ArrayList<>();
    private final List<Spatial> vanishing = new ArrayList<>();
    private final List<Node> explosions = new ArrayList<>();
    private final List<Node> players = new ArrayList<>();
    private final List<Geometry> bodies = new ArrayList<>();
    private final List<Geometry> clouds = new ArrayList<>();
    private final List<Geometry> hitboxes = new ArrayList<>();
    private final float[] walkTime;
    private final float[] deathTime;
    private float time;

    ArenaScene(AssetManager assets, BitmapFont font, Simulation sim, ThemeDefinition theme, boolean teamPlay, boolean flicker) {
        this.assets = assets;
        this.font = font;
        this.sim = sim;
        this.theme = theme;
        this.flicker = flicker;
        // Bombman's frame replaces the outer ring of walls: stretched over the inner arena plus a 37 px border.
        var frame = new Node("Frame");
        frame.attachChild(sprite(material(SPRITES + "other_map_background.png"), (sim.width() - 2) / PX + 74,
                (sim.height() - 2) * ROW / PX + 74, 0, 0, -.05f));
        frame.setLocalTranslation(bounds(sim.width(), sim.height())[0], 0, bounds(sim.width(), sim.height())[1]);
        root.attachChild(frame);
        solidAtStart = new boolean[sim.width() * sim.height()];
        for (int z = 1; z < sim.height() - 1; z++) {
            for (int x = 1; x < sim.width() - 1; x++) {
                solidAtStart[z * sim.width() + x] = sim.block(x, z) == '#';
                root.attachChild(tile(theme.texture("floor"), x, z, FLOOR, null));
                if (sim.block(x, z) == '#') root.attachChild(tile(theme.texture("solid"), x, z, TILE, null));
                if (sim.belt(x, z) != Direction.NONE) root.attachChild(belt(x, z, sim.belt(x, z)));
                if (sim.warpExit(x, z) != null) root.attachChild(warp(x, z));
            }
        }
        float hitbox = sim.rules().hitbox() / (float) Simulation.SUB / PX;
        for (var p : sim.players()) {
            var node = new Node("Player " + (p.id() + 1));
            if (teamPlay) {
                node.attachChild(sprite(material(SPRITES + "other_shadow.png", TEAM_COLORS[p.team() % TEAM_COLORS.length]),
                        64, 58, 32, 29, SHADOW - .002f));
            }
            node.attachChild(sprite(material(SPRITES + "other_shadow.png"), 50, 45, 25, 22.5f, SHADOW));
            var body = sprite(playerMaterial(p.id(), "down"), 60, 94, PLAYER_X, PLAYER_Y, ACTOR);
            node.attachChild(body);
            bodies.add(body);
            var cloud = sprite(material(SPRITES + "animation_disease1.png"), 50, 50, 25, 95, EFFECT);
            node.attachChild(cloud);
            clouds.add(cloud);
            hitboxes.add(sprite(material(SPRITES + "other_shadow.png", new ColorRGBA(1, 0, 1, 1)), hitbox, hitbox * ROW,
                    hitbox / 2, hitbox * ROW / 2, EFFECT));
            players.add(node);
            root.attachChild(node);
        }
        walkTime = new float[players.size()];
        deathTime = new float[players.size()];
    }

    /** World rectangle the camera must show: the frame around the inner arena (left, top, right, bottom). */
    static float[] bounds(int width, int height) {
        return new float[] {.5f - 37 * PX, .5f * ROW - 37 * PX, width - 1.5f + 37 * PX, (height - 1.5f) * ROW + 37 * PX};
    }

    /** Transparent sprites are drawn lowest layer first, then by screen row, as Bombman sorts by position. */
    static final class LayerOrder implements GeometryComparator {
        @Override public void setCamera(Camera camera) { }

        @Override public int compare(Geometry a, Geometry b) {
            int byLayer = Float.compare(a.getWorldTranslation().y, b.getWorldTranslation().y);
            return byLayer != 0 ? byLayer : Float.compare(a.getWorldTranslation().z, b.getWorldTranslation().z);
        }
    }

    /** Explosions start from simulation events. */
    void onEvents(List<SimulationEvent> events) {
        for (var e : events) {
            if (e.type() != SimulationEvent.Type.EXPLOSION) continue;
            var burst = new Node("Explosion");
            burst.setUserData("age", 0f);
            burst.setLocalTranslation(e.x(), 0, e.z() * ROW);
            explosions.add(burst);
            root.attachChild(burst);
        }
    }

    /**
     * Brings views in line with the latest tick and animates; player positions blend from
     * the previous tick by {@code alpha} so motion stays smooth at any frame rate.
     */
    void update(float tpf, int[] previousX, int[] previousZ, float alpha, boolean debug) {
        time += tpf;
        var wantedBlocks = new HashMap<Integer, Integer>();
        var wantedFlames = new HashMap<Integer, Integer>();
        var wantedItems = new HashMap<Integer, Item>();
        var wantedClosed = new HashMap<Integer, Integer>();
        for (int z = 1; z < sim.height() - 1; z++) {
            for (int x = 1; x < sim.width() - 1; x++) {
                int cell = z * sim.width() + x;
                if (sim.block(x, z) == 'B') wantedBlocks.put(cell, cell);
                if (sim.item(x, z) != null) wantedItems.put(cell, sim.item(x, z));
                if (sim.block(x, z) == '#' && !solidAtStart[cell]) wantedClosed.put(cell, cell);
            }
        }
        for (var f : sim.flames()) wantedFlames.put(f.z() * sim.width() + f.x(), f.owner());
        var wantedWarnings = new HashMap<Integer, Integer>();
        for (var c : sim.closingSoon()) wantedWarnings.put(c.z() * sim.width() + c.x(), 0);
        var wantedBombs = new HashMap<Integer, Simulation.Bomb>();
        for (var b : sim.bombs()) wantedBombs.put(b.id(), b);
        // Sudden death: announced cells blink red, closed cells drop in from above as reddened walls.
        sync(warnings, wantedWarnings, cell -> tile(SPRITES + "other_shadow.png", cell % sim.width(), cell / sim.width(), DECOR,
                new ColorRGBA(1, .1f, .1f, 1)), false);
        warnings.values().forEach(v -> v.setCullHint((int) (time * 8) % 2 == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always));
        sync(closed, wantedClosed, cell -> {
            var wall = tile(theme.texture("solid"), cell % sim.width(), cell / sim.width(), TILE + .001f, new ColorRGBA(1, .55f, .55f, 1));
            wall.move(0, 0, -3);
            return wall;
        }, false);
        closed.forEach((cell, view) -> view.setLocalTranslation(view.getLocalTranslation().x, 0,
                Math.min(cell / sim.width() * ROW, view.getLocalTranslation().z + tpf * 12)));
        sync(blocks, wantedBlocks, cell -> tile(theme.texture("crate"), cell % sim.width(), cell / sim.width(), TILE, null), true);
        sync(flames, wantedFlames, owner -> sprite(material(SPRITES + "flame1.png"), 50, 45, 25, 22.5f, FLAME), false);
        sync(items, wantedItems, this::item, true);
        sync(bombs, wantedBombs, this::bomb, false);

        flames.forEach((cell, view) -> {
            view.setLocalTranslation(cell % sim.width() - 25 * PX, FLAME, cell / sim.width() * ROW + 22.5f * PX);
            ((Geometry) view).setMaterial(material(flameSprite(cell, wantedFlames.keySet())));
        });
        items.forEach((cell, view) -> view.setLocalTranslation(cell % sim.width(), 0, cell / sim.width() * ROW));
        for (var node : belts) {
            Direction d = Direction.valueOf(node.getUserData("dir"));
            float shift = (time * sim.rules().beltSpeed() * Simulation.TICKS_PER_SECOND / Simulation.SUB) % 1f;
            for (int i = 1; i < node.getQuantity(); i++) {
                float along = ((i - 1) / 2f + shift) % 1f - .5f;
                var arrow = node.getChild(i);
                arrow.setLocalTranslation(d.dx() * along - 15 * PX, DECOR + .001f, d.dz() * along * ROW + 13.5f * PX);
            }
        }
        animateExplosions(tpf);
        for (Iterator<Spatial> it = vanishing.iterator(); it.hasNext(); ) {
            var view = it.next();
            float left = (float) view.getUserData("vanish") - tpf;
            view.setUserData("vanish", left);
            view.setLocalScale(Math.max(0, left / VANISH_SECONDS), 1, Math.max(0, left / VANISH_SECONDS));
            if (left <= 0) {
                view.removeFromParent();
                it.remove();
            }
        }
        for (var p : sim.players()) animatePlayer(p, tpf, previousX, previousZ, alpha, debug);
        for (var b : sim.bombs()) animateBomb(b, alpha);
    }

    private void animatePlayer(Simulation.Player p, float tpf, int[] previousX, int[] previousZ, float alpha, boolean debug) {
        int id = p.id();
        var node = players.get(id);
        float x = FastMath.interpolateLinear(alpha, previousX[id], p.x()) / Simulation.SUB - .5f;
        float z = (FastMath.interpolateLinear(alpha, previousZ[id], p.z()) / Simulation.SUB - .5f) * ROW;
        node.setLocalTranslation(x, 0, z);
        var body = bodies.get(id);
        boolean moving = p.x() != previousX[id] || p.z() != previousZ[id];
        String direction = p.facing() == Direction.NONE ? "down" : p.facing().name().toLowerCase(Locale.ROOT);
        boolean dying = !p.alive();
        String frame;
        if (dying) {
            deathTime[id] += tpf;
            frame = "animation_die" + Math.min(7, 1 + (int) (deathTime[id] / DEATH_SECONDS * 7));
            node.setCullHint(deathTime[id] > DEATH_SECONDS ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        } else if (p.carrying()) {
            frame = "player_" + direction + "_box";
        } else if (moving) {
            walkTime[id] += tpf * p.speed() / 3000f;
            boolean side = direction.equals("left") || direction.equals("right");
            frame = "player_" + switch ((int) (walkTime[id] * 10) % 4) {
                case 0 -> direction + "_walk1";
                case 2 -> direction + "_walk3";
                default -> side ? direction : direction + "_walk2";
            };
        } else {
            frame = "player_" + direction;
        }
        body.setMaterial(recolored(frame, id));
        // Lower rows are drawn over upper rows; the winner hops.
        float hop = sim.isOver() && p.alive() && sim.winner() == p.team() ? Math.abs(FastMath.sin(time * 9)) * .25f : 0;
        float width = dying ? 54 : 60;
        float height = dying ? 85 : 94;
        if (dying != Boolean.TRUE.equals(body.getUserData("dying"))) { // dying frames are 54 x 85 px
            ((Quad) body.getMesh()).updateGeometry(width * PX, height * PX);
            body.setUserData("dying", dying);
        }
        body.setLocalTranslation(-(dying ? DYING_X : PLAYER_X) * PX, ACTOR + z * .001f, (height - (dying ? DYING_Y : PLAYER_Y)) * PX - hop);
        boolean blink = p.alive() && p.stunnedUntil() > sim.tick() && (int) (time * 12) % 2 == 0;
        body.setCullHint(blink ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        var cloud = clouds.get(id);
        boolean sick = p.alive() && !p.effects().isEmpty();
        cloud.setCullHint(sick ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        if (sick) cloud.setMaterial(material(SPRITES + "animation_disease" + (1 + (int) (time * 10) % 6) + ".png"));
        var box = hitboxes.get(id);
        if (debug && p.alive()) node.attachChild(box);
        else box.removeFromParent();
    }

    private void animateBomb(Simulation.Bomb b, float alpha) {
        var node = (Node) bombs.get(b.id());
        // A calm 1-2-3-2 pulse, one frame per 200 ms and twice as fast in the last half second; trigger bombs
        // wait still with their antenna, as in Bombman. Counted in ticks, so the pulse stops with the game.
        long left = b.explodesAt() - sim.tick();
        long pulse = left / (left < Simulation.TICKS_PER_SECOND / 2 ? 6 : 12);
        int frame = b.trigger() ? 1 : new int[] {1, 2, 3, 2}[(int) Math.floorMod(-pulse, 4L)];
        var color = PLAYER_COLORS[b.owner() % PLAYER_COLORS.length];
        float x;
        float z;
        float lift = 0;
        float layer = ACTOR;
        switch (b.state()) {
            case CARRIED -> {
                var carrier = players.get(b.carrier()).getLocalTranslation();
                x = carrier.x;
                z = carrier.z;
                lift = 1.55f;
                layer = ACTOR + .02f;
            }
            case AIRBORNE -> {
                float t = Math.clamp((sim.tick() - 1 + alpha - b.flyStart()) / (float) (b.flyEnd() - b.flyStart()), 0f, 1f);
                x = wrap(b.x() + b.flyDirection().dx() * b.flyCells() * t, sim.width());
                z = wrap(b.z() + b.flyDirection().dz() * b.flyCells() * t, sim.height()) * ROW;
                lift = 1.2f * t * (1 - t) * (1 + b.flyCells());
                layer = FLYING;
            }
            default -> {
                float step = b.speed() * alpha;
                x = (b.px() + b.rolling().dx() * step) / Simulation.SUB - .5f;
                z = ((b.pz() + b.rolling().dz() * step) / Simulation.SUB - .5f) * ROW;
            }
        }
        node.setLocalTranslation(x, 0, z);
        for (var child : node.getChildren()) {
            var geometry = (Geometry) child;
            float baseZ = geometry.getUserData("z");
            float order = "Shadow".equals(geometry.getName()) ? SHADOW : layer + z * .001f + geometry.<Float>getUserData("over");
            geometry.setLocalTranslation(geometry.getLocalTranslation().x, order, baseZ - ("Shadow".equals(geometry.getName()) ? 0 : lift));
        }
        ((Geometry) node.getChild("Body")).setMaterial(material(SPRITES + "bomb" + frame + ".png", color));
        node.getChild("Shadow").setCullHint(b.state() == Simulation.BombState.CARRIED ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    }

    private void animateExplosions(float tpf) {
        for (Iterator<Node> it = explosions.iterator(); it.hasNext(); ) {
            var burst = it.next();
            float age = (float) burst.getUserData("age") + tpf;
            burst.setUserData("age", age);
            int frame = (int) (age / EXPLOSION_SECONDS * 10) + 1;
            burst.detachAllChildren();
            if (frame > 10) {
                burst.removeFromParent();
                it.remove();
                continue;
            }
            burst.attachChild(sprite(material(SPRITES + "animation_explosion" + frame + ".png"), 70, 83, 35, 55, EFFECT));
        }
    }

    /** Flights leaving the inner arena reappear on the other side, as in the simulation. */
    private static float wrap(float cell, int size) {
        int inner = size - 2;
        while (cell > size - 1.5f) cell -= inner;
        while (cell < .5f) cell += inner;
        return cell;
    }

    private <T> void sync(Map<Integer, Spatial> views, Map<Integer, T> wanted, Function<T, Spatial> create, boolean fade) {
        views.entrySet().removeIf(entry -> {
            if (wanted.containsKey(entry.getKey())) return false;
            if (fade) {
                entry.getValue().setUserData("vanish", VANISH_SECONDS);
                vanishing.add(entry.getValue());
            } else {
                entry.getValue().removeFromParent();
            }
            return true;
        });
        wanted.forEach((key, value) -> views.computeIfAbsent(key, k -> {
            var view = create.apply(value);
            root.attachChild(view);
            return view;
        }));
    }

    // ------------------------------------------------------------ pieces

    /** A full cell image (50 × 45 px) centred on its cell. */
    private Node tile(String path, int x, int z, float layer, ColorRGBA tint) {
        var node = new Node("Tile");
        node.attachChild(sprite(tint == null ? material(path) : material(path, tint), 50, 45, 25, 22.5f, layer));
        node.setLocalTranslation(x, 0, z * ROW);
        return node;
    }

    /** Darkened floor with two arrows sliding along the belt, so the push is visible. */
    private Node belt(int x, int z, Direction d) {
        var node = tile(SPRITES + "other_shadow.png", x, z, DECOR, new ColorRGBA(0, 0, 0, 1));
        node.setUserData("dir", d.name());
        for (int i = 0; i < 2; i++) {
            node.attachChild(sprite(material(SPRITES + "other_arrow_" + d.name().toLowerCase(Locale.ROOT) + ".png"),
                    30, 27, 0, 0, DECOR + .001f));
        }
        belts.add(node);
        return node;
    }

    /** Bombman's teleport pad with the pair letter, so pairs are told apart without relying on colour. */
    private Node warp(int x, int z) {
        var node = tile(SPRITES + "other_teleport.png", x, z, DECOR, null);
        var text = new BitmapText(font);
        text.setSize(.3f);
        text.setColor(ColorRGBA.White);
        text.setText(String.valueOf(pairLetter(x, z)));
        text.setQueueBucket(Bucket.Transparent);
        var label = new Node("Letter");
        label.attachChild(text);
        label.setLocalRotation(new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X));
        label.setLocalTranslation(.24f, DECOR + .002f, .4f);
        node.attachChild(label);
        return node;
    }

    /** Pairs are lettered A, B, C... in reading order of their first cell. */
    private char pairLetter(int x, int z) {
        var exit = sim.warpExit(x, z);
        int first = Math.min(z * sim.width() + x, exit.z() * sim.width() + exit.x());
        int index = 0;
        for (int cell = 0; cell < first; cell++) {
            var other = sim.warpExit(cell % sim.width(), cell / sim.width());
            if (other != null && other.z() * sim.width() + other.x() > cell) index++;
        }
        return (char) ('A' + index);
    }

    /** A bomb in its owner's colour, as in Atomic Bomberman, with a shadow; trigger bombs carry an antenna, jelly bombs a spring. */
    private Spatial bomb(Simulation.Bomb b) {
        var node = new Node("Bomb " + b.id());
        node.attachChild(bombPart("Shadow", material(SPRITES + "other_shadow.png"), 50, 45, 25, 22.5f, 0));
        node.attachChild(bombPart("Body", material(SPRITES + "bomb1.png"), 50, 43, BOMB_X, BOMB_Y, 0));
        if (b.jelly()) node.attachChild(bombPart("Spring", material(SPRITES + "other_spring.png"), 50, 43, BOMB_X, BOMB_Y, .001f));
        if (b.trigger()) node.attachChild(bombPart("Antenna", material(SPRITES + "other_antena.png"), 20, 40, 4, BOMB_Y + 25, .002f));
        return node;
    }

    private static Geometry bombPart(String name, Material material, float w, float h, float ax, float ay, float over) {
        var part = sprite(material, w, h, ax, ay, ACTOR);
        part.setName(name);
        part.setUserData("z", part.getLocalTranslation().z);
        part.setUserData("over", over);
        return part;
    }

    /** Bombman's pictogram tile; Super Disease shows its tile reddened. */
    private Spatial item(Item item) {
        var node = new Node("Item " + item);
        var tint = item == Item.SUPER_DISEASE ? new ColorRGBA(1, .45f, .45f, 1) : ColorRGBA.White;
        node.attachChild(sprite(material(itemSprite(item), tint), 50, 45, 25, 22.5f, ITEM));
        return node;
    }

    static String itemSprite(Item item) {
        return SPRITES + switch (item) {
            case BOMB_UP -> "item_bomb";
            case FLAME -> "item_flame";
            case SKATE -> "item_speedup";
            case KICK -> "item_shoe";
            case PUNCH -> "item_boxing_glove";
            case GRAB -> "item_throwing_glove";
            case SPOOGE -> "item_multibomb";
            case TRIGGER -> "item_detonator";
            case JELLY -> "item_spring";
            case GOLDFLAME -> "item_superflame";
            case DISEASE, SUPER_DISEASE -> "item_disease";
            case RANDOM -> "item_random";
        } + ".png";
    }

    /** Small HUD icon of an ability or of being sick (null for items without one). */
    static String iconSprite(Item item) {
        String name = switch (item) {
            case KICK -> "icon_kicking_shoe";
            case PUNCH -> "icon_boxing_glove";
            case GRAB -> "icon_throwing_glove";
            case SPOOGE -> "icon_multibomb";
            case TRIGGER -> "icon_detonator";
            case JELLY -> "icon_spring";
            case GOLDFLAME -> "icon_flame";
            case DISEASE, SUPER_DISEASE -> "icon_disease";
            default -> null;
        };
        return name == null ? null : SPRITES + name + ".png";
    }

    /** Ray sprite from the burning neighbours: centre, straight piece or the tip at the end of a ray. */
    private String flameSprite(int cell, Set<Integer> lit) {
        int w = sim.width();
        boolean l = lit.contains(cell - 1), r = lit.contains(cell + 1), u = lit.contains(cell - w), d = lit.contains(cell + w);
        String shape = (l || r) && (u || d) ? "" : l && r ? "_horizontal" : u && d ? "_vertical"
                : r ? "_left" : l ? "_right" : d ? "_up" : u ? "_down" : "";
        int frame = flicker && (int) (time * 10) % 2 == 1 ? 2 : 1;
        return SPRITES + "flame" + frame + shape + ".png";
    }

    // ------------------------------------------------------------ sprites, textures, materials

    /**
     * A quad lying on the floor, image top towards the top of the screen. The anchor (in sprite pixels from
     * the top left) sits on the parent's position; {@code layer} is the drawing height.
     */
    private static Geometry sprite(Material material, float pixelWidth, float pixelHeight, float anchorX, float anchorY, float layer) {
        var quad = new Geometry("Sprite", new Quad(pixelWidth * PX, pixelHeight * PX));
        quad.setMaterial(material);
        quad.setQueueBucket(Bucket.Transparent);
        quad.setLocalRotation(new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X));
        quad.setLocalTranslation(-anchorX * PX, layer, (pixelHeight - anchorY) * PX);
        return quad;
    }

    private Texture2D texture(String path) {
        return textures.computeIfAbsent(path, p -> {
            var texture = (Texture2D) assets.loadTexture(p);
            texture.setMagFilter(Texture.MagFilter.Nearest); // keep the pixel art crisp when enlarged
            return texture;
        });
    }

    private Material material(String path) {
        return material(path, ColorRGBA.White);
    }

    private Material material(String path, ColorRGBA tint) {
        return materials.computeIfAbsent(path + "@" + tint, key -> unshaded(texture(path), tint));
    }

    private Material unshaded(Texture2D texture, ColorRGBA tint) {
        var material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", texture);
        material.setColor("Color", tint);
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        return material;
    }

    private Material playerMaterial(int slot, String frame) {
        return recolored("player_" + frame, slot);
    }

    /**
     * The whole robot in the slot colour, once per sprite and slot: Bombman's pure red marks (255, 0, 0) take the
     * colour as is, grey shading is tinted (body white 230 = the colour, darker greys darker, highlights towards white).
     * The black outline and visor (grey below 60) stay.
     */
    private Material recolored(String name, int slot) {
        String path = SPRITES + name + ".png";
        return materials.computeIfAbsent(path + "#" + slot, key -> {
            try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("missing sprite " + path);
                var image = javax.imageio.ImageIO.read(stream);
                var argb = new java.awt.image.BufferedImage(image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(image, 0, 0, null);
                var c = PLAYER_COLORS[slot % PLAYER_COLORS.length];
                var eyes = eyes(argb);
                int pale = tint(new ColorRGBA(c.r + (1 - c.r) * .7f, c.g + (1 - c.g) * .7f, c.b + (1 - c.b) * .7f, 1), 230);
                for (int y = 0; y < argb.getHeight(); y++) {
                    for (int x = 0; x < argb.getWidth(); x++) {
                        int pixel = argb.getRGB(x, y);
                        int r = pixel >> 16 & 0xFF, g = pixel >> 8 & 0xFF, b = pixel & 0xFF;
                        if (pixel >>> 24 == 0) continue;
                        if (eyes[y * argb.getWidth() + x]) argb.setRGB(x, y, pixel & 0xFF000000 | pale);
                        else if (r == 255 && g == 0 && b == 0) argb.setRGB(x, y, pixel & 0xFF000000 | tint(c, 230));
                        else if (r == g && g == b && r >= 60) argb.setRGB(x, y, pixel & 0xFF000000 | tint(c, r));
                    }
                }
                var texture = new Texture2D(new AWTLoader().load(argb, true));
                texture.setMagFilter(Texture.MagFilter.Nearest);
                return unshaded(texture, ColorRGBA.White);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    /** Eyes are the red marks inside the black visor: red areas that touch no grey shading of 60 and more. */
    static boolean[] eyes(java.awt.image.BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        var eyes = new boolean[w * h];
        var seen = new boolean[w * h];
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || !red(image, start % w, start / w)) continue;
            var area = new ArrayList<Integer>(List.of(start));
            seen[start] = true;
            boolean shaded = false;
            for (int i = 0; i < area.size(); i++) {
                int x = area.get(i) % w, y = area.get(i) / w;
                for (int[] n : new int[][] {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}}) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= w || n[1] >= h) continue;
                    int at = n[1] * w + n[0];
                    if (red(image, n[0], n[1])) {
                        if (!seen[at]) area.add(at);
                        seen[at] = true;
                    } else {
                        int pixel = image.getRGB(n[0], n[1]);
                        shaded |= pixel >>> 24 > 0 && (pixel >> 16 & 0xFF) >= 60;
                    }
                }
            }
            if (!shaded) for (int at : area) eyes[at] = true;
        }
        return eyes;
    }

    private static boolean red(java.awt.image.BufferedImage image, int x, int y) {
        int pixel = image.getRGB(x, y);
        return pixel >>> 24 > 0 && (pixel & 0xFFFFFF) == 0xFF0000;
    }

    /** RGB of the slot colour shaded like a grey of the sprite. */
    static int tint(ColorRGBA c, int grey) {
        float s = grey / 230f;
        int rgb = 0;
        for (float v : new float[] {c.r, c.g, c.b}) {
            float out = s <= 1 ? v * s : v + (1 - v) * (s - 1) * 4;
            rgb = rgb << 8 | Math.round(Math.min(1, out) * 255);
        }
        return rgb;
    }

    private static ColorRGBA rgb(int r, int g, int b) {
        return new ColorRGBA(r / 255f, g / 255f, b / 255f, 1);
    }

    /** HUD text colour of a slot: lightened like Bombman's coloured text so blue and black read on the dark HUD. */
    static ColorRGBA textColor(int slot) {
        var c = PLAYER_COLORS[slot % PLAYER_COLORS.length];
        return new ColorRGBA(c.r + (1 - c.r) * .35f, c.g + (1 - c.g) * .35f, c.b + (1 - c.b) * .35f, 1);
    }
}
