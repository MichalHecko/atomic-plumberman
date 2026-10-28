package sk.atomicplumberman.desktop;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.AudioKey;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.JmeSystem;
import org.junit.jupiter.api.Test;
import sk.atomicplumberman.content.ArenaJson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class AssetImportTest {
    private final DesktopAssetManager assets = new DesktopAssetManager(JmeSystem.getPlatformAssetConfigURL());

    @Test void bundledGlbImportsMaterialGeometryAndAnAnimationThatActuallyMoves() {
        Spatial model = assets.loadModel(PreviewAssets.MODEL);
        assertTrue(model.getTriangleCount() >= 12);
        var composer = PreviewAssets.composer(model);
        assertNotNull(composer);
        assertTrue(composer.hasAnimClip(PreviewAssets.CLIP));
        assertEquals(2.0, composer.getAnimClip(PreviewAssets.CLIP).getLength(), .001);
        Spatial cube = ((Node) model).getChild("ProbeCube");
        assertNotNull(cube);
        composer.setCurrentAction(PreviewAssets.CLIP);
        model.updateLogicalState(0);
        var before = cube.getLocalTranslation().clone();
        model.updateLogicalState(.5f);
        model.updateLogicalState(0);
        assertTrue(cube.getLocalTranslation().y > before.y + .2f, "Imported clip must move the cube up");
    }

    @Test void eyesAreTheRedMarksInsideTheVisor() throws java.io.IOException {
        for (var frame : new String[] {"player_down", "player_left", "player_up"}) {
            try (var stream = getClass().getClassLoader().getResourceAsStream("Textures/Bombman/" + frame + ".png")) {
                var eyes = ArenaScene.eyes(javax.imageio.ImageIO.read(Objects.requireNonNull(stream)));
                int count = 0;
                for (boolean eye : eyes) if (eye) count++;
                assertEquals(switch (frame) { case "player_down" -> 64; case "player_left" -> 20; default -> 0; }, count, frame);
            }
        }
    }

    @Test void everyReferencedBombmanSpriteLoads() {
        var paths = new java.util.ArrayList<String>();
        for (var item : sk.atomicplumberman.core.Item.values()) {
            paths.add(ArenaScene.itemSprite(item));
            if (ArenaScene.iconSprite(item) != null) paths.add(ArenaScene.iconSprite(item));
        }
        for (String frame : new String[] {"1", "2"}) {
            for (String shape : new String[] {"", "_horizontal", "_vertical", "_left", "_right", "_up", "_down"}) {
                paths.add("Textures/Bombman/flame" + frame + shape + ".png");
            }
        }
        for (int i = 1; i <= 10; i++) paths.add("Textures/Bombman/animation_explosion" + i + ".png");
        for (String d : new String[] {"up", "down", "left", "right"}) paths.add("Textures/Bombman/other_arrow_" + d + ".png");
        for (String name : new String[] {"other_teleport", "other_shadow", "other_antena", "other_spring", "other_map_background",
                "bomb1", "bomb2", "bomb3"}) {
            paths.add("Textures/Bombman/" + name + ".png");
        }
        for (String d : new String[] {"up", "down", "left", "right"}) {
            for (String pose : new String[] {"", "_box", "_walk1", "_walk3"}) paths.add("Textures/Bombman/player_" + d + pose + ".png");
        }
        paths.add("Textures/Bombman/player_up_walk2.png");
        paths.add("Textures/Bombman/player_down_walk2.png");
        for (int i = 1; i <= 7; i++) paths.add("Textures/Bombman/animation_die" + i + ".png");
        for (int i = 1; i <= 6; i++) paths.add("Textures/Bombman/animation_disease" + i + ".png");
        for (String path : paths) assertNotNull(assets.loadTexture(path).getImage(), path);
    }

    @Test void bundledAudioDecodesWithoutAnAudioDevice() {
        var audio = assets.loadAudio(new AudioKey(PreviewAssets.SOUND, false));
        assertEquals(1, audio.getChannels());
        assertEquals(22050, audio.getSampleRate());
        assertEquals(.24, audio.getDuration(), .01);
        for (String name : new String[] {"place", "explosion", "pickup", "death", "go", "fanfare"}) {
            var effect = assets.loadAudio(new AudioKey("Audio/Sfx/" + name + ".wav", false));
            assertTrue(effect.getDuration() > .05 && effect.getDuration() < 1, name);
        }
    }

    @Test void bundledArenaIsValidAndReferenceImagesAreNotRuntimeAssets() throws Exception {
        var loader = getClass().getClassLoader();
        try (var reader = new InputStreamReader(Objects.requireNonNull(loader.getResourceAsStream(MatchState.ARENA)),
                StandardCharsets.UTF_8)) {
            var arena = ArenaJson.read(reader, MatchState.ARENA);
            assertEquals(17, arena.width());
            assertEquals(13, arena.height());
            assertEquals(4, arena.starts().size());
        }
        assertNull(loader.getResource("docs/references/atomic-bomberman/01-classic-arena.jpg"));
    }
}
