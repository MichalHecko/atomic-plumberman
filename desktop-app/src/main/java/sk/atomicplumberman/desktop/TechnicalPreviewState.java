package sk.atomicplumberman.desktop;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.font.BitmapText;
import com.jme3.input.Joystick;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.*;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import sk.atomicplumberman.content.ArenaJson;
import sk.atomicplumberman.core.ArenaLayout;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Disposable M0 integration scene; moving the marker is an input probe, not game logic. */
public final class TechnicalPreviewState extends BaseAppState implements RawInputListener {
    private final boolean audioEnabled;
    private final Node scene = new Node("M0 scene");
    private final Node overlay = new Node("M0 overlay");
    private final Node marker = new Node("Input probe");
    private SimpleApplication app;
    private ArenaLayout arena;
    private AudioNode sound;
    private BitmapText title;
    private BitmapText help;
    private BitmapText status;
    private String lastInput = "Waiting for keyboard / gamepad input";
    private int markerX = 8;
    private int markerZ = 5;
    private int width;
    private int height;

    public TechnicalPreviewState(boolean audioEnabled) { this.audioEnabled = audioEnabled; }

    @Override protected void initialize(Application application) {
        app = (SimpleApplication) application;
        try (var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(MatchState.ARENA));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            arena = ArenaJson.read(reader, MatchState.ARENA);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read preview arena", exception);
        }
        app.getViewPort().setBackgroundColor(new ColorRGBA(.025f, .04f, .065f, 1));
        scene.addLight(new AmbientLight(new ColorRGBA(.65f, .65f, .7f, 1)));
        scene.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal(), ColorRGBA.White));

        var floorA = material(new ColorRGBA(.12f, .22f, .25f, 1));
        var floorB = material(new ColorRGBA(.15f, .26f, .29f, 1));
        var wall = material(new ColorRGBA(.28f, .38f, .47f, 1));
        var brick = material(new ColorRGBA(.78f, .36f, .12f, 1));
        for (int z = 0; z < arena.height(); z++) {
            for (int x = 0; x < arena.width(); x++) {
                scene.attachChild(box("Floor", x, -.12f, z, .49f, .12f, .49f, (x + z) % 2 == 0 ? floorA : floorB));
                char cell = arena.cell(x, z);
                if (cell == '#') scene.attachChild(box("Solid block", x, .42f, z, .48f, .42f, .48f, wall));
                if (cell == 'B' || cell == '?') scene.attachChild(box("Breakable preview", x, .32f, z, .41f, .32f, .41f, brick));
            }
        }
        var model = app.getAssetManager().loadModel(PreviewAssets.MODEL);
        var composer = Objects.requireNonNull(PreviewAssets.composer(model), "GLB has no AnimComposer");
        composer.setCurrentAction(PreviewAssets.CLIP);
        marker.attachChild(model);
        marker.setLocalTranslation(markerX, 0, markerZ);
        scene.attachChild(marker);
        if (audioEnabled) {
            if (app.getAudioRenderer() == null) throw new IllegalStateException("OpenAL unavailable; use --no-audio to diagnose");
            sound = new AudioNode(app.getAssetManager(), PreviewAssets.SOUND, AudioData.DataType.Buffer);
            sound.setPositional(false);
            sound.setVolume(.35f);
            scene.attachChild(sound);
        }
        title = text("ATOMIC PLUMBERMAN  /  M0", 25, new ColorRGBA(.35f, .9f, .85f, 1));
        help = text("Arrows / WASD: move probe    Space: sound    Esc: exit\nGLB: ProbeBounce    Camera: orthographic    Arena: 17 x 13", 17, ColorRGBA.White);
        status = text("", 15, new ColorRGBA(.8f, .85f, .9f, 1));
        Joystick[] joysticks = app.getInputManager().getJoysticks();
        System.out.println("M0_GAMEPADS: " + (joysticks == null ? 0 : joysticks.length));
        if (joysticks != null) for (Joystick joystick : joysticks) {
            System.out.printf("M0_GAMEPAD: id=%d name=%s axes=%s buttons=%s%n",
                    joystick.getJoyId(), joystick.getName(), joystick.getAxes(), joystick.getButtons());
        }
        refreshStatus();
    }

    private Material material(ColorRGBA color) {
        var material = new Material(app.getAssetManager(), "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", color);
        material.setColor("Ambient", color);
        return material;
    }

    private static Geometry box(String name, float x, float y, float z, float a, float b, float c, Material material) {
        var geometry = new Geometry(name, new Box(a, b, c));
        geometry.setMaterial(material);
        geometry.setLocalTranslation(x, y, z);
        return geometry;
    }

    private BitmapText text(String value, float size, ColorRGBA color) {
        var label = new BitmapText(app.getAssetManager().loadFont("Interface/Fonts/Default.fnt"));
        label.setSize(size);
        label.setColor(color);
        label.setText(value);
        overlay.attachChild(label);
        return label;
    }

    @Override public void update(float tpf) {
        var camera = app.getCamera();
        if (width != camera.getWidth() || height != camera.getHeight()) {
            width = camera.getWidth();
            height = camera.getHeight();
            OrthographicArenaCamera.fit(camera, -.5f, -.5f, arena.width() - .5f, arena.height() - .5f);
            float scale = Math.min(1f, width / 1000f);
            overlay.setLocalScale(scale);
            title.setLocalTranslation(24, height / scale - 20, 0);
            help.setLocalTranslation(24, height / scale - 56, 0);
            status.setLocalTranslation(24, 45, 0);
        }
    }

    private void refreshStatus() {
        var pads = app.getInputManager().getJoysticks();
        status.setText("Audio: " + (audioEnabled ? "OpenAL" : "disabled") + "    Gamepads: "
                + (pads == null ? 0 : pads.length) + "\n" + lastInput);
    }

    private void move(int dx, int dz) {
        int x = markerX + dx;
        int z = markerZ + dz;
        if (x >= 0 && z >= 0 && x < arena.width() && z < arena.height() && arena.cell(x, z) == '.') {
            markerX = x;
            markerZ = z;
            marker.setLocalTranslation(markerX, 0, markerZ);
        }
    }

    private void playSound() { if (sound != null) sound.play(); }

    @Override public void onKeyEvent(KeyInputEvent event) {
        if (!event.isPressed() || event.isRepeating()) return;
        lastInput = "Keyboard key=" + event.getKeyCode();
        switch (event.getKeyCode()) {
            case KeyInput.KEY_UP, KeyInput.KEY_W -> move(0, -1);
            case KeyInput.KEY_DOWN, KeyInput.KEY_S -> move(0, 1);
            case KeyInput.KEY_LEFT, KeyInput.KEY_A -> move(-1, 0);
            case KeyInput.KEY_RIGHT, KeyInput.KEY_D -> move(1, 0);
            case KeyInput.KEY_SPACE -> playSound();
            default -> { }
        }
        refreshStatus();
        System.out.println("M0_INPUT: " + lastInput);
    }

    @Override public void onJoyAxisEvent(JoyAxisEvent event) {
        if (Math.abs(event.getValue()) < .2f) return;
        lastInput = "Gamepad " + event.getJoyIndex() + " axis " + event.getAxis().getName()
                + " = " + String.format(java.util.Locale.ROOT, "%.2f", event.getValue());
        refreshStatus();
    }

    @Override public void onJoyButtonEvent(JoyButtonEvent event) {
        lastInput = "Gamepad " + event.getJoyIndex() + " button " + event.getButtonIndex()
                + (event.isPressed() ? " pressed" : " released");
        if (event.isPressed()) playSound();
        refreshStatus();
        System.out.println("M0_INPUT: " + lastInput);
    }

    @Override protected void onEnable() {
        app.getRootNode().attachChild(scene);
        app.getGuiNode().attachChild(overlay);
        app.getInputManager().addRawInputListener(this);
        playSound();
    }

    @Override protected void onDisable() {
        app.getInputManager().removeRawInputListener(this);
        if (sound != null) sound.stop();
        scene.removeFromParent();
        overlay.removeFromParent();
    }

    @Override protected void cleanup(Application application) {
        scene.detachAllChildren();
        overlay.detachAllChildren();
    }

    @Override public void beginInput() { }
    @Override public void endInput() { }
    @Override public void onMouseMotionEvent(MouseMotionEvent event) { }
    @Override public void onMouseButtonEvent(MouseButtonEvent event) { }
    @Override public void onTouchEvent(TouchEvent event) { }
}
