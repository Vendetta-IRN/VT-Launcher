package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class Manifest {
    public record VersionEntry(String id, String type, String url) {}

    private static final String URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private Manifest() {}

    private static volatile List<VersionEntry> cache;

    /** Fetches the version list in the background at startup so it is ready when the page opens. */
    public static void preload() {
        Thread.ofVirtual().start(() -> {
            try {
                load();
            } catch (Exception ignored) {
            }
        });
    }

    /** Forget the cached list (used by the Reload button). */
    public static void refresh() {
        cache = null;
    }

    public static List<VersionEntry> load() throws IOException, InterruptedException {
        List<VersionEntry> c = cache;
        if (c != null) return c;
        byte[] b = Net.getBytes(URL);
        JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        List<VersionEntry> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            out.add(new VersionEntry(v.get("id").getAsString(), v.get("type").getAsString(), v.get("url").getAsString()));
        }
        cache = out;
        return out;
    }

    /** Version ids that have a folder + json inside gameDir/versions. */
    public static List<String> installed(Path gameDir) {
        List<String> out = new ArrayList<>();
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return out;
        try (Stream<Path> s = Files.list(versions)) {
            s.filter(Files::isDirectory).forEach(d -> {
                String id = d.getFileName().toString();
                if (Files.exists(d.resolve(id + ".json"))) out.add(id);
            });
        } catch (IOException ignored) {
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }
}
