package sk.atomicplumberman.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ThemeAndManifestTest {
    @Test void bundledThemesAreValidWithExistingFiles() throws Exception {
        var ids = new java.util.HashSet<String>();
        try (var files = Files.list(Path.of("../assets/runtime/Data/themes"))) {
            for (var file : files.toList()) {
                try (var reader = Files.newBufferedReader(file)) {
                    var theme = ThemeJson.read(reader, file.toString());
                    assertTrue(Files.isRegularFile(Path.of("../assets/runtime").resolve(theme.music())), theme.music());
                    for (String texture : theme.textures().values()) {
                        assertTrue(Files.isRegularFile(Path.of("../assets/runtime").resolve(texture)), texture);
                    }
                    ids.add(theme.id());
                }
            }
        }
        assertEquals(7, ids.size(), ids.toString());
    }

    @Test void themeErrorsNameTheField() {
        var error = assertThrows(IllegalArgumentException.class, () -> ThemeJson.read(new StringReader("""
                {"schemaVersion":2,"id":"x","name":"X","background":"red","music":"m","textures":{}}"""), "x.json"));
        assertTrue(error.getMessage().startsWith("x.json: background"), error.getMessage());
        var missing = assertThrows(IllegalArgumentException.class, () -> ThemeJson.read(new StringReader("""
                {"schemaVersion":2,"id":"x","name":"X","background":"#000000","music":"m","textures":{"floor":"f"}}"""), "x.json"));
        assertTrue(missing.getMessage().contains("textures: expected exactly"), missing.getMessage());
    }

    @Test void projectManifestCoversEveryRuntimeAsset() throws Exception {
        var root = Path.of("..").toAbsolutePath().normalize();
        assertEquals(List.of(), AssetManifest.problems(root, root.resolve("assets/runtime"),
                root.resolve("docs/asset-manifest.csv"), root.resolve("docs/references")));
    }

    @Test void unlistedFilesMissingFilesAndCopiedReferencesAreReported(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("assets/runtime"));
        Files.createDirectories(root.resolve("docs/references"));
        Files.writeString(root.resolve("docs/references/shot.png"), "reference");
        Files.writeString(root.resolve("assets/runtime/copy.png"), "reference");
        Files.writeString(root.resolve("manifest.csv"), "id,author,origin,license,file,modification\n"
                + "gone,me,here,CC0,assets/runtime/gone.wav,\n");
        var problems = AssetManifest.problems(root, root.resolve("assets/runtime"), root.resolve("manifest.csv"),
                root.resolve("docs/references"));
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing file assets/runtime/gone.wav")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("copy.png: not in the asset manifest")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("copy of a reference image")));
    }
}
