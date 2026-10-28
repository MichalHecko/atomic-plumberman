package sk.atomicplumberman.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Plan 10.2: every runtime asset has a manifest row with author, origin and licence, every row points
 * at an existing file, and no runtime file is a copy of a documentation reference image.
 */
public final class AssetManifest {
    private static final String HEADER = "id,author,origin,license,file,modification";

    private AssetManifest() { }

    /** Paths in the manifest are relative to {@code projectRoot}. */
    public static List<String> problems(Path projectRoot, Path runtime, Path manifest, Path references) throws IOException {
        var problems = new ArrayList<String>();
        var lines = Files.readAllLines(manifest);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) return List.of(manifest + ": expected header " + HEADER);
        var listed = new HashSet<Path>();
        var ids = new HashSet<String>();
        for (int i = 1; i < lines.size(); i++) {
            var cells = lines.get(i).split(",", -1);
            String where = manifest.getFileName() + " line " + (i + 1);
            if (cells.length != 6 || java.util.Arrays.stream(cells).limit(5).anyMatch(String::isBlank)) {
                problems.add(where + ": expected six columns with id, author, origin, license and file");
                continue;
            }
            if (!ids.add(cells[0])) problems.add(where + ": duplicate id " + cells[0]);
            var file = projectRoot.resolve(cells[4]).normalize();
            if (!Files.isRegularFile(file)) problems.add(where + ": missing file " + cells[4]);
            listed.add(file);
        }
        var referenceHashes = new HashSet<String>();
        if (Files.isDirectory(references)) {
            try (Stream<Path> files = Files.walk(references)) {
                for (var file : files.filter(Files::isRegularFile).toList()) referenceHashes.add(sha256(file));
            }
        }
        try (Stream<Path> files = Files.walk(runtime)) {
            for (var file : files.filter(Files::isRegularFile).map(Path::normalize).sorted().toList()) {
                var name = projectRoot.relativize(file);
                if (!listed.contains(file)) problems.add(name + ": not in the asset manifest");
                if (referenceHashes.contains(sha256(file))) problems.add(name + ": is a copy of a reference image from docs");
            }
        }
        return problems;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
