package sk.atomicplumberman.content;

import java.util.Map;
import java.util.Set;

/**
 * Look of an arena, independent of its layout (plan 6.1): window background as 0xRRGGBB, the music
 * loop and tile textures for {@code floor}, {@code solid} and {@code crate}. Any layout works with any
 * theme; the simulation never sees it.
 */
public record ThemeDefinition(String id, String name, int background, String music, Map<String, String> textures) {
    public static final Set<String> TEXTURES = Set.of("floor", "solid", "crate");

    public ThemeDefinition {
        textures = Map.copyOf(textures);
        if (!textures.keySet().equals(TEXTURES)) throw new IllegalArgumentException("textures: expected exactly " + TEXTURES);
    }

    public String texture(String key) { return textures.get(key); }
}
