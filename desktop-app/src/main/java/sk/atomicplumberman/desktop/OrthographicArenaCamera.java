package sk.atomicplumberman.desktop;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/** Straight top-down orthographic view, like the original game; nothing rotates or zooms during a round. */
public final class OrthographicArenaCamera {
    private OrthographicArenaCamera() { }

    /**
     * Fits the world rectangle (left, top, right, bottom on the XZ plane; top is smaller Z) below a HUD band
     * that scales with the window like the HUD text, keeping cells undistorted at any aspect ratio.
     */
    public static void fit(Camera camera, float left, float top, float right, float bottom) {
        float hud = Math.min(camera.getWidth() / 1280f, camera.getHeight() / 720f);
        float topInset = 76 * hud;
        float bottomInset = 22 * hud;
        float usable = Math.max(1, camera.getHeight() - topInset - bottomInset);
        float aspect = (float) camera.getWidth() / Math.max(1, camera.getHeight());
        float halfHeight = Math.max((bottom - top) / 2 * camera.getHeight() / usable, (right - left) * 1.02f / 2 / aspect);
        float halfWidth = halfHeight * aspect;
        // Shift the view so the content sits in the band between the insets.
        float worldPerPixel = 2 * halfHeight / camera.getHeight();
        var center = new Vector3f((left + right) / 2, 0, (top + bottom) / 2 - (topInset - bottomInset) / 2 * worldPerPixel);
        camera.setParallelProjection(true);
        camera.setFrustum(.1f, 100f, -halfWidth, halfWidth, halfHeight, -halfHeight);
        camera.setLocation(center.add(0, 50, 0));
        camera.lookAt(center, new Vector3f(0, 0, -1));
    }
}
