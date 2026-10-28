package sk.atomicplumberman.desktop;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.scene.Node;
import java.util.HashMap;
import java.util.Map;

/**
 * Sound groups of plan 3.4: music, effects and UI with separate volumes under the master volume.
 * The same effect is not restarted within {@link #COOLDOWN_SECONDS}, so chain reactions and ten
 * players do not stack copies. Without an audio device every call is a no-op.
 */
final class AudioMixer {
    private static final float COOLDOWN_SECONDS = .06f;
    private final AssetManager assets;
    private final Settings settings;
    private final Node owner = new Node("Audio");
    private final Map<String, AudioNode> effects = new HashMap<>();
    private final Map<String, Float> lastPlayed = new HashMap<>();
    private final boolean enabled;
    private AudioNode music;
    private float time;

    AudioMixer(AssetManager assets, Settings settings, Node parent, boolean enabled) {
        this.assets = assets;
        this.settings = settings;
        this.enabled = enabled;
        parent.attachChild(owner);
    }

    void update(float tpf) { time += tpf; }

    /** Plays {@code Audio/Sfx/<name>.wav} at the effects volume. */
    void effect(String name) {
        if (!enabled || time - lastPlayed.getOrDefault(name, -1f) < COOLDOWN_SECONDS) return;
        lastPlayed.put(name, time);
        var node = effects.computeIfAbsent(name, n -> {
            var created = new AudioNode(assets, "Audio/Sfx/" + n + ".wav", AudioData.DataType.Buffer);
            created.setPositional(false);
            owner.attachChild(created);
            return created;
        });
        node.setVolume(.6f * settings.effectsVolume / 100f);
        node.playInstance();
    }

    /** Starts a music loop, replacing any other; the same loop keeps playing. */
    void music(String path) {
        if (!enabled || music != null && path.equals(music.getUserData("path"))) return;
        stopMusic();
        music = new AudioNode(assets, path, AudioData.DataType.Buffer);
        music.setUserData("path", path);
        music.setPositional(false);
        music.setLooping(true);
        owner.attachChild(music);
        applyVolumes();
        music.play();
    }

    void stopMusic() {
        if (music == null) return;
        music.stop();
        music.removeFromParent();
        music = null;
    }

    void applyVolumes() {
        if (music != null) music.setVolume(.5f * settings.musicVolume / 100f);
    }
}
