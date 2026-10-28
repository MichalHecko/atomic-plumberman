package sk.atomicplumberman.content;

import sk.atomicplumberman.core.RulesetDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates content files: JSON in a {@code themes} directory is a theme, any other JSON an arena.
 * {@code --assets <project root>} also checks the asset manifest. Exit codes: 0 ok, 1 invalid, 2 usage.
 */
public final class ContentValidator {
    private ContentValidator() { }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: content-tools [--assets <project root>] <arena-or-theme.json> ...");
            System.exit(2);
        }
        boolean failed = false;
        for (int i = 0; i < args.length; i++) {
            String source = args[i];
            List<String> problems = new ArrayList<>();
            String summary = "";
            try {
                if (source.equals("--assets") && i + 1 < args.length) {
                    var root = Path.of(args[++i]);
                    source = root.resolve("docs/asset-manifest.csv").toString();
                    problems = AssetManifest.problems(root, root.resolve("assets/runtime"), root.resolve("docs/asset-manifest.csv"),
                            root.resolve("docs/references"));
                    summary = "every runtime asset listed with its origin and licence";
                } else if (Path.of(source).getParent() != null && Path.of(source).getParent().getFileName().toString().equals("themes")) {
                    try (var reader = Files.newBufferedReader(Path.of(source), StandardCharsets.UTF_8)) {
                        var theme = ThemeJson.read(reader, source);
                        summary = "theme " + theme.id();
                    }
                } else {
                    try (var reader = Files.newBufferedReader(Path.of(source), StandardCharsets.UTF_8)) {
                        var arena = ArenaJson.read(reader, source);
                        problems = ArenaValidator.problems(arena, RulesetDefinition.CLASSIC);
                        summary = String.format("%s (%d x %d, %d starts)", arena.id(), arena.width(), arena.height(), arena.starts().size());
                    }
                }
            } catch (IOException | IllegalArgumentException exception) {
                problems = List.of(exception.getMessage());
            }
            for (String problem : problems) System.err.println("INVALID " + source + ": " + problem);
            if (problems.isEmpty()) System.out.println("OK " + source + ": " + summary);
            failed |= !problems.isEmpty();
        }
        if (failed) System.exit(1);
    }
}
