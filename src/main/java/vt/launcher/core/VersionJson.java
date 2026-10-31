package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Loads a version json and merges it with its parent when it uses "inheritsFrom" (Fabric, Forge, OptiFine...). */
public final class VersionJson {
    private VersionJson() {}

    public static JsonObject load(Path gameDir, String id) throws IOException {
        return load(gameDir, id, 0);
    }

    private static JsonObject load(Path gameDir, String id, int depth) throws IOException {
        if (depth > 5) throw new IOException("inheritsFrom loop in " + id);
        Path f = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(f)) throw new IOException("Version json not found: " + id);
        JsonObject v = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        if (v.has("inheritsFrom")) {
            JsonObject parent = load(gameDir, v.get("inheritsFrom").getAsString(), depth + 1);
            return merge(parent, v);
        }
        v.addProperty("clientJarId", v.has("jar") ? v.get("jar").getAsString() : id);
        return v;
    }

    private static JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject r = JsonParser.parseString(parent.toString()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : child.entrySet()) {
            String k = e.getKey();
            if (k.equals("libraries")) {
                JsonArray merged = new JsonArray();
                merged.addAll(e.getValue().getAsJsonArray());
                if (r.has("libraries")) merged.addAll(r.getAsJsonArray("libraries"));
                r.add("libraries", merged);
            } else if (k.equals("arguments")) {
                mergeArgs(r, e.getValue().getAsJsonObject());
            } else {
                r.add(k, e.getValue());
            }
        }
        if (child.has("jar")) r.addProperty("clientJarId", child.get("jar").getAsString());
        return r;
    }

    private static void mergeArgs(JsonObject r, JsonObject childArgs) {
        JsonObject base = r.has("arguments") ? r.getAsJsonObject("arguments") : new JsonObject();
        for (String key : new String[]{"game", "jvm"}) {
            if (!childArgs.has(key)) continue;
            JsonArray merged = new JsonArray();
            if (base.has(key)) merged.addAll(base.getAsJsonArray(key));
            merged.addAll(childArgs.getAsJsonArray(key));
            base.add(key, merged);
        }
        r.add("arguments", base);
    }
}
