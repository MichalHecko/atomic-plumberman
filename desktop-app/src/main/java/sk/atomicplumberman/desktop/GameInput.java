package sk.atomicplumberman.desktop;

import com.jme3.input.Joystick;
import com.jme3.input.JoystickAxis;
import com.jme3.input.JoystickConnectionListener;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.PlayerInputFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Devices are two keyboard profiles and gamepads by GLFW joystick ID. Each produces a held
 * direction (latest wins) plus bomb/action edges; OS key repeat is ignored. Menu navigation
 * edges come from any device, plus the mouse.
 */
final class GameInput implements RawInputListener, JoystickConnectionListener {
    static final int KEYBOARD_A = 0;
    static final int KEYBOARD_B = 1;
    static final int FIRST_PAD = 2;
    static final int MAX_PADS = 16;
    private static final int DEVICES = FIRST_PAD + MAX_PADS;
    private static final Direction[] DIRECTIONS = {Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT};
    private static final Menu[] MENU_DIRECTIONS = {Menu.UP, Menu.DOWN, Menu.LEFT, Menu.RIGHT};
    private static final float DEADZONE = .5f;
    private static final float AXIS_HYSTERESIS = .2f;

    enum Menu { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, PAUSE, DEBUG }

    private final int[][] keys;
    private final List<List<Direction>> held = List.of(new ArrayList<>(), new ArrayList<>());
    /** Per pad: stick x, stick y, d-pad x, d-pad y. */
    private final float[][] axes = new float[MAX_PADS][4];
    private final Direction[] pad = new Direction[MAX_PADS];
    private final boolean[] connected = new boolean[MAX_PADS];
    private final boolean[] bomb = new boolean[DEVICES];
    private final boolean[] bombHeld = new boolean[DEVICES];
    private final boolean[] action = new boolean[DEVICES];
    private final EnumSet<Menu> menu = EnumSet.noneOf(Menu.class);
    private float mouseX;
    private float mouseY;
    private boolean click;
    private int pressedKey = -1;

    GameInput(int[][] keys) {
        this.keys = keys;
        releaseAll();
    }

    static String deviceName(int device) {
        return switch (device) {
            case KEYBOARD_A -> "Keyboard 1";
            case KEYBOARD_B -> "Keyboard 2";
            default -> "Gamepad " + (device - FIRST_PAD + 1);
        };
    }

    String controls(int device) {
        if (device >= FIRST_PAD) return "stick / D-pad, A bomb, B action";
        int[] k = keys[device];
        return Settings.keyName(k[0]) + " " + Settings.keyName(k[2]) + " " + Settings.keyName(k[1]) + " " + Settings.keyName(k[3])
                + ", " + Settings.keyName(k[4]) + " bomb, " + Settings.keyName(k[5]) + " action";
    }

    boolean connected(int device) { return device < FIRST_PAD || connected[device - FIRST_PAD]; }

    /** Keyboards and the gamepads currently plugged in. */
    List<Integer> devices() {
        var devices = new ArrayList<>(List.of(KEYBOARD_A, KEYBOARD_B));
        for (int i = 0; i < MAX_PADS; i++) if (connected[i]) devices.add(FIRST_PAD + i);
        return devices;
    }

    Direction direction(int device) {
        if (device >= FIRST_PAD) return pad[device - FIRST_PAD];
        var list = held.get(device);
        return list.isEmpty() ? Direction.NONE : list.getLast();
    }

    /** One frame per slot for this tick; consumes the bomb edges of those devices. */
    List<PlayerInputFrame> frames(int[] slotDevices) {
        var frames = new ArrayList<PlayerInputFrame>();
        for (int device : slotDevices) {
            frames.add(connected(device)
                    ? new PlayerInputFrame(direction(device), consumeBomb(device), bombHeld[device], consumeAction(device))
                    : PlayerInputFrame.NEUTRAL);
        }
        return frames;
    }

    boolean consumeBomb(int device) {
        boolean value = bomb[device];
        bomb[device] = false;
        return value;
    }

    boolean consumeAction(int device) {
        boolean value = action[device];
        action[device] = false;
        return value;
    }

    boolean consume(Menu edge) { return menu.remove(edge); }

    /** The last key pressed since the previous call or {@link #clearEdges}, or -1; for remapping. */
    int consumeKey() {
        int key = pressedKey;
        pressedKey = -1;
        return key;
    }

    float mouseX() { return mouseX; }
    float mouseY() { return mouseY; }

    boolean consumeClick() {
        boolean value = click;
        click = false;
        return value;
    }

    /** Drop presses made outside of play, e.g. the key that dismissed a screen. */
    void clearEdges() {
        Arrays.fill(bomb, false);
        Arrays.fill(action, false);
        menu.clear();
        click = false;
        pressedKey = -1;
    }

    /** Focus loss: release events will not arrive, so nothing may stay held. */
    void releaseAll() {
        held.forEach(List::clear);
        Arrays.fill(bombHeld, false);
        for (float[] values : axes) Arrays.fill(values, 0);
        Arrays.fill(pad, Direction.NONE);
        clearEdges();
    }

    @Override public void onConnected(Joystick joystick) {
        int id = joystick.getJoyId();
        if (id >= 0 && id < MAX_PADS) connected[id] = true;
    }

    @Override public void onDisconnected(Joystick joystick) {
        int id = joystick.getJoyId();
        if (id < 0 || id >= MAX_PADS) return;
        connected[id] = false;
        Arrays.fill(axes[id], 0);
        pad[id] = Direction.NONE;
        bomb[FIRST_PAD + id] = false;
        bombHeld[FIRST_PAD + id] = false;
        action[FIRST_PAD + id] = false;
    }

    @Override public void onKeyEvent(KeyInputEvent event) {
        if (event.isRepeating()) return;
        int code = event.getKeyCode();
        if (event.isPressed()) {
            pressedKey = code;
            switch (code) {
                case KeyInput.KEY_RETURN, KeyInput.KEY_NUMPADENTER, KeyInput.KEY_SPACE -> menu.add(Menu.CONFIRM);
                case KeyInput.KEY_ESCAPE -> {
                    menu.add(Menu.BACK);
                    menu.add(Menu.PAUSE);
                }
                case KeyInput.KEY_BACK -> menu.add(Menu.BACK);
                case KeyInput.KEY_P -> menu.add(Menu.PAUSE);
                case KeyInput.KEY_F3 -> menu.add(Menu.DEBUG);
                default -> { }
            }
        }
        for (int profile = 0; profile < keys.length; profile++) {
            for (int i = 0; i < DIRECTIONS.length; i++) {
                if (keys[profile][i] != code) continue;
                held.get(profile).remove(DIRECTIONS[i]);
                if (event.isPressed()) {
                    held.get(profile).add(DIRECTIONS[i]);
                    menu.add(MENU_DIRECTIONS[i]);
                }
            }
            if (keys[profile][4] == code) {
                bomb[profile] |= event.isPressed();
                bombHeld[profile] = event.isPressed();
            }
            if (event.isPressed() && keys[profile][5] == code) action[profile] = true;
        }
    }

    // ponytail: axis signs follow GLFW (stick y < 0 is up) and jME POV (pov_y > 0 is up); unverified on a physical pad
    @Override public void onJoyAxisEvent(JoyAxisEvent event) {
        int id = event.getJoyIndex();
        if (id < 0 || id >= MAX_PADS) return;
        connected[id] = true;
        int index = switch (event.getAxis().getLogicalId()) {
            case JoystickAxis.X_AXIS -> 0;
            case JoystickAxis.Y_AXIS -> 1;
            case JoystickAxis.POV_X -> 2;
            case JoystickAxis.POV_Y -> 3;
            default -> -1;
        };
        if (index < 0) return;
        axes[id][index] = event.getValue();
        float[] a = axes[id];
        boolean dpad = Math.abs(a[2]) >= DEADZONE || Math.abs(a[3]) >= DEADZONE;
        var before = pad[id];
        pad[id] = dpad ? direction(a[2], -a[3], before) : direction(a[0], a[1], before);
        if (pad[id] != before && pad[id] != Direction.NONE) menu.add(MENU_DIRECTIONS[pad[id].ordinal() - 1]);
    }

    /** Screen direction from x/y (y down), keeping the current axis until the other one clearly dominates. */
    static Direction direction(float x, float y, Direction current) {
        if (Math.max(Math.abs(x), Math.abs(y)) < DEADZONE) return Direction.NONE;
        boolean wasHorizontal = current == Direction.LEFT || current == Direction.RIGHT;
        boolean horizontal = Math.abs(x) > Math.abs(y) + (wasHorizontal ? -AXIS_HYSTERESIS : AXIS_HYSTERESIS);
        if (horizontal) return x > 0 ? Direction.RIGHT : Direction.LEFT;
        return y > 0 ? Direction.DOWN : Direction.UP;
    }

    @Override public void onJoyButtonEvent(JoyButtonEvent event) {
        int id = event.getJoyIndex();
        if (id < 0 || id >= MAX_PADS) return;
        connected[id] = true;
        if (event.getButton().getLogicalId().equals("0")) bombHeld[FIRST_PAD + id] = event.isPressed();
        if (!event.isPressed()) return;
        switch (event.getButton().getLogicalId()) {
            case "0" -> { // A
                bomb[FIRST_PAD + id] = true;
                menu.add(Menu.CONFIRM);
            }
            case "1" -> { // B: Action in a match, back in menus
                action[FIRST_PAD + id] = true;
                menu.add(Menu.BACK);
            }
            case "6" -> menu.add(Menu.BACK); // Back / Select
            case "7" -> menu.add(Menu.PAUSE); // Start
            default -> { }
        }
    }

    @Override public void onMouseMotionEvent(MouseMotionEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
    }

    @Override public void onMouseButtonEvent(MouseButtonEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
        if (event.isPressed() && event.getButtonIndex() == MouseInput.BUTTON_LEFT) click = true;
    }

    @Override public void beginInput() { }
    @Override public void endInput() { }
    @Override public void onTouchEvent(TouchEvent event) { }
}
