package sk.atomicplumberman.desktop;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class OrthographicArenaCameraTest {
    @ParameterizedTest
    @CsvSource({"1280,720", "1920,1080", "1280,800", "1024,768", "640,480", "720,1280"})
    void wholeFrameFitsBelowTheHudUndistorted(int width, int height) {
        var camera = new Camera(width, height);
        float left = -.24f, top = -.29f, right = 15.74f, bottom = 10.64f; // classic 15 x 11 cells plus Bombman's frame
        OrthographicArenaCamera.fit(camera, left, top, right, bottom);
        assertTrue(camera.isParallelProjection());
        float hud = Math.min(width / 1280f, height / 720f);
        for (float x : new float[] {left, right}) {
            for (float z : new float[] {top, bottom}) {
                var screen = camera.getScreenCoordinates(new Vector3f(x, .5f, z));
                assertTrue(screen.x >= -.5f && screen.x <= width + .5f, "clipped horizontally: " + screen);
                assertTrue(screen.y >= 22 * hud - .5f && screen.y <= height - 76 * hud + .5f, "overlaps the HUD: " + screen);
            }
        }
        var a = camera.getScreenCoordinates(new Vector3f(5, 0, 5));
        var right1 = camera.getScreenCoordinates(new Vector3f(6, 0, 5));
        var up1 = camera.getScreenCoordinates(new Vector3f(5, 0, 4));
        assertTrue(up1.y > a.y, "decreasing Z moves up on screen");
        assertEquals(right1.x - a.x, up1.y - a.y, .01f, "one world unit is as long across as up, so 50 x 45 px tiles keep their shape");
    }
}
