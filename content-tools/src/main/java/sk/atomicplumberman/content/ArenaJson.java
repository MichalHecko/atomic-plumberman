package sk.atomicplumberman.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import sk.atomicplumberman.core.ArenaLayout;
import sk.atomicplumberman.core.Cell;
import sk.atomicplumberman.core.Item;
import java.io.Reader;
import java.util.ArrayList;

/** IO/JSON boundary shared by the validator and the desktop, never by the simulation. */
public final class ArenaJson {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private ArenaJson() { }

    public static ArenaLayout read(Reader reader, String source) {
        try {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) throw new IllegalArgumentException("document: expected an object");
            JsonElement version = root.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || !version.getAsString().equals("2")) {
                throw new IllegalArgumentException("schemaVersion: expected integer 2");
            }
            String id = string(root.get("id"), "id");
            if (!(root.get("rows") instanceof JsonArray rows)) {
                throw new IllegalArgumentException("rows: expected an array");
            }
            var values = new ArrayList<String>();
            for (int i = 0; i < rows.size(); i++) values.add(string(rows.get(i), "rows[" + i + "]"));
            if (!(root.get("starts") instanceof JsonArray starts) || starts.size() < 2) {
                throw new IllegalArgumentException("starts: expected an array of at least 2 [x, z] cells");
            }
            var cells = new ArrayList<Cell>();
            for (int i = 0; i < starts.size(); i++) {
                if (!(starts.get(i) instanceof JsonArray pair) || pair.size() != 2) {
                    throw new IllegalArgumentException("starts[" + i + "]: expected [x, z]");
                }
                cells.add(new Cell(integer(pair.get(0), "starts[" + i + "][0]"), integer(pair.get(1), "starts[" + i + "][1]")));
            }
            boolean teams = false;
            if (root.has("teams")) {
                if (!root.get("teams").isJsonPrimitive() || !root.get("teams").getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("teams: expected true or false");
                }
                teams = root.get("teams").getAsBoolean();
            }
            var startItems = new ArrayList<Item>();
            if (root.has("startItems")) {
                if (!(root.get("startItems") instanceof JsonArray names)) {
                    throw new IllegalArgumentException("startItems: expected an array of item names");
                }
                for (int i = 0; i < names.size(); i++) startItems.add(item(names.get(i), "startItems[" + i + "]"));
            }
            var itemPool = new ArrayList<Item>();
            if (root.has("itemPool")) {
                if (!(root.get("itemPool") instanceof JsonObject counts)) {
                    throw new IllegalArgumentException("itemPool: expected an object of item counts");
                }
                for (var entry : counts.entrySet()) {
                    Item item = item(new com.google.gson.JsonPrimitive(entry.getKey()), "itemPool." + entry.getKey());
                    for (int n = integer(entry.getValue(), "itemPool." + entry.getKey()); n > 0; n--) itemPool.add(item);
                }
            }
            var rules = new java.util.HashMap<String, Integer>();
            if (root.has("rules")) {
                if (!(root.get("rules") instanceof JsonObject overrides)) throw new IllegalArgumentException("rules: expected an object");
                for (var entry : overrides.entrySet()) rules.put(entry.getKey(), integer(entry.getValue(), "rules." + entry.getKey()));
            }
            String name = root.has("name") ? string(root.get("name"), "name") : id;
            String theme = root.has("theme") ? string(root.get("theme"), "theme") : "";
            return new ArenaLayout(id, name, values, cells, teams, startItems, itemPool, rules, theme);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(source + ": " + exception.getMessage(), exception);
        }
    }

    private static Item item(JsonElement value, String field) {
        String name = string(value, field);
        try {
            return Item.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(field + ": unknown item " + name);
        }
    }

    private static int integer(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?\\d{1,9}")) {
            throw new IllegalArgumentException(field + ": expected an integer");
        }
        return value.getAsInt();
    }

    private static String string(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + ": expected a string");
        }
        return value.getAsString();
    }
}
