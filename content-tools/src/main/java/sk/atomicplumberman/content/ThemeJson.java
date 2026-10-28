package sk.atomicplumberman.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.Reader;
import java.util.HashMap;

/** Strict reader of theme JSON; errors name the source and the field. */
public final class ThemeJson {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private ThemeJson() { }

    public static ThemeDefinition read(Reader reader, String source) {
        try {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) throw new IllegalArgumentException("document: expected an object");
            if (!(root.get("schemaVersion") instanceof JsonElement v) || !v.isJsonPrimitive() || !v.getAsString().equals("2")) {
                throw new IllegalArgumentException("schemaVersion: expected integer 2");
            }
            String background = string(root.get("background"), "background");
            if (!background.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("background: expected #RRGGBB");
            if (!(root.get("textures") instanceof JsonObject textureObject)) throw new IllegalArgumentException("textures: expected an object");
            var textures = new HashMap<String, String>();
            for (var entry : textureObject.entrySet()) textures.put(entry.getKey(), string(entry.getValue(), "textures." + entry.getKey()));
            return new ThemeDefinition(string(root.get("id"), "id"), string(root.get("name"), "name"),
                    Integer.parseInt(background.substring(1), 16), string(root.get("music"), "music"), textures);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(source + ": " + exception.getMessage(), exception);
        }
    }

    private static String string(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + ": expected a string");
        }
        return value.getAsString();
    }
}
