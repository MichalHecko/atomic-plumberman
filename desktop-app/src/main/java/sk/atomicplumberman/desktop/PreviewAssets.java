package sk.atomicplumberman.desktop;

import com.jme3.anim.AnimComposer;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

public final class PreviewAssets {
    public static final String MODEL = "Models/Diagnostics/animated-probe.glb";
    public static final String SOUND = "Audio/Diagnostics/probe.wav";
    public static final String CLIP = "ProbeBounce";

    private PreviewAssets() { }

    public static AnimComposer composer(Spatial model) {
        var control = model.getControl(AnimComposer.class);
        if (control != null) return control;
        if (model instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                var found = composer(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
