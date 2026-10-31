package vt.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Renaming an installed version (folder, json, jar, versions built on it, profiles that use it). */
public final class VersionManager {
    private VersionManager() {}

    /** "name" if free, otherwise "name (1)", "name (2)"... so an existing version is never replaced. */
    public static String uniqueId(Path root, String id) {
        Path versions = root.resolve("versions");
        String cand = id;
        int i = 1;
        while (Files.exists(versions.resolve(cand))) cand = id + " (" + i++ + ")";
        return cand;
    }

    /** Moves a version folder to a new name (folder, json, jar, "id" inside the json). Nothing else is touched. */
    public static void relocate(Path root, String from, String to) throws IOException {
        Path versions = root.resolve("versions");
        Path dst = versions.resolve(to);
        Files.move(versions.resolve(from), dst);
        Path oldJson = dst.resolve(from + ".json");
        JsonObject v = JsonParser.parseString(Files.readString(oldJson, StandardCharsets.UTF_8)).getAsJsonObject();
        v.addProperty("id", to);
        Files.writeString(dst.resolve(to + ".json"), v.toString(), StandardCharsets.UTF_8);
        Files.deleteIfExists(oldJson);
        Path oldJar = dst.resolve(from + ".jar");
        if (Files.exists(oldJar)) Files.move(oldJar, dst.resolve(to + ".jar"));
    }

    public static void rename(Config cfg, String old, String neu) throws IOException {
        if (neu == null || neu.isBlank()) throw new IOException("The name cannot be empty.");
        if (!neu.matches("[^\\\\/:*?\"<>|]+")) throw new IOException("The name cannot contain  \\ / : * ? \" < > |");
        if (neu.equals(old)) return;
        Path root = cfg.gameDirPath();
        Path versions = root.resolve("versions");
        Path from = versions.resolve(old);
        Path to = versions.resolve(neu);
        if (!Files.isDirectory(from)) throw new IOException("Version folder not found: " + old);
        if (Files.exists(to)) throw new IOException("A version named '" + neu + "' already exists.");

        Files.move(from, to);
        try {
            Path oldJson = to.resolve(old + ".json");
            JsonObject v = JsonParser.parseString(Files.readString(oldJson, StandardCharsets.UTF_8)).getAsJsonObject();
            // remember which Minecraft version this really is
            if (!v.has("inheritsFrom") && !v.has("vt_mc_version")) v.addProperty("vt_mc_version", old);
            v.addProperty("id", neu);
            Files.writeString(to.resolve(neu + ".json"), v.toString(), StandardCharsets.UTF_8);
            Files.deleteIfExists(oldJson);
            Path oldJar = to.resolve(old + ".jar");
            if (Files.exists(oldJar)) Files.move(oldJar, to.resolve(neu + ".jar"));
        } catch (IOException | RuntimeException e) {
            Files.move(to, from); // undo
            throw new IOException("Could not rename: " + e.getMessage());
        }

        // versions that are built on this one
        for (String other : Manifest.installed(root)) {
            if (other.equals(neu)) continue;
            Path f = versions.resolve(other).resolve(other + ".json");
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("inheritsFrom") && o.get("inheritsFrom").getAsString().equals(old)) {
                    o.addProperty("inheritsFrom", neu);
                    Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }

        for (Profile p : cfg.profiles) if (old.equals(p.version)) p.version = neu;
        if (cfg.versionOpts.containsKey(old)) cfg.versionOpts.put(neu, cfg.versionOpts.remove(old));
        cfg.save();
    }
}
