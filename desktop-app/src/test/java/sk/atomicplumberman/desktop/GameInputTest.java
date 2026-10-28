package sk.atomicplumberman.desktop;

import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import org.junit.jupiter.api.Test;
import sk.atomicplumberman.core.Direction;
import sk.atomicplumberman.core.PlayerInputFrame;
import static org.junit.jupiter.api.Assertions.*;

class GameInputTest {
    private static final int[] KEYBOARDS = {GameInput.KEYBOARD_A, GameInput.KEYBOARD_B};
    private final GameInput input = new GameInput(new int[][] {Settings.DEFAULT_KEYS[0].clone(), Settings.DEFAULT_KEYS[1].clone()});

    private void key(int code, boolean pressed, boolean repeating) {
        input.onKeyEvent(new KeyInputEvent(code, ' ', pressed, repeating));
    }

    private PlayerInputFrame p1() { return input.frames(KEYBOARDS).getFirst(); }

    @Test void latestHeldDirectionWinsAndReleaseFallsBack() {
        key(KeyInput.KEY_UP, true, false);
        key(KeyInput.KEY_RIGHT, true, false);
        assertEquals(Direction.RIGHT, p1().direction());
        key(KeyInput.KEY_RIGHT, false, false);
        assertEquals(Direction.UP, p1().direction());
        key(KeyInput.KEY_W, true, false);
        assertEquals(Direction.UP, p1().direction(), "keyboard 2 does not move P1");
        assertEquals(Direction.UP, input.frames(KEYBOARDS).get(1).direction());
    }

    @Test void bombIsOneEdgeAndKeyRepeatIsIgnored() {
        key(KeyInput.KEY_SPACE, true, false);
        key(KeyInput.KEY_SPACE, true, true);
        assertTrue(p1().bomb());
        assertFalse(p1().bomb(), "held key and OS repeat must not place another bomb");
        key(KeyInput.KEY_SPACE, false, false);
        key(KeyInput.KEY_SPACE, true, false);
        input.clearEdges();
        assertFalse(p1().bomb(), "a press that dismissed a screen is dropped");
    }

    @Test void bombHoldAndActionReachTheFrame() {
        key(KeyInput.KEY_SPACE, true, false);
        var pressed = p1();
        assertTrue(pressed.bomb() && pressed.bombHeld());
        var held = p1();
        assertTrue(!held.bomb() && held.bombHeld(), "held without a new press: Grab keeps carrying");
        key(KeyInput.KEY_SPACE, false, false);
        assertFalse(p1().bombHeld(), "release throws");
        key(KeyInput.KEY_RETURN, true, false);
        assertTrue(p1().action());
        assertFalse(p1().action(), "action is an edge");
    }

    @Test void remappedKeysDriveTheProfile() {
        var remapped = new GameInput(new int[][] {{KeyInput.KEY_I, KeyInput.KEY_K, KeyInput.KEY_J, KeyInput.KEY_L,
                KeyInput.KEY_O, KeyInput.KEY_U}, Settings.DEFAULT_KEYS[1].clone()});
        remapped.onKeyEvent(new KeyInputEvent(KeyInput.KEY_J, ' ', true, false));
        remapped.onKeyEvent(new KeyInputEvent(KeyInput.KEY_O, ' ', true, false));
        assertEquals(new PlayerInputFrame(Direction.LEFT, true, true, false), remapped.frames(KEYBOARDS).getFirst());
    }

    @Test void focusLossReleasesEverythingAndMissingDevicesStayNeutral() {
        key(KeyInput.KEY_LEFT, true, false);
        input.releaseAll();
        assertEquals(PlayerInputFrame.NEUTRAL, p1());
        assertFalse(input.connected(GameInput.FIRST_PAD), "gamepad 1 is not plugged in");
        assertEquals(PlayerInputFrame.NEUTRAL, input.frames(new int[] {GameInput.FIRST_PAD}).getFirst());
    }

    @Test void stickNeedsDeadzoneAndHysteresisBetweenAxes() {
        assertEquals(Direction.NONE, GameInput.direction(.3f, .2f, Direction.NONE));
        assertEquals(Direction.RIGHT, GameInput.direction(.8f, .7f, Direction.RIGHT), "stays horizontal near the diagonal");
        assertEquals(Direction.DOWN, GameInput.direction(.7f, .8f, Direction.NONE));
        assertEquals(Direction.UP, GameInput.direction(.1f, -.9f, Direction.RIGHT));
    }
}
