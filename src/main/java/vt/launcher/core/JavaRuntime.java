package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vt.launcher.core.Downloader.Job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Finds or downloads the Java a Minecraft version needs.
 * Order: imported (local) Java with the right major version, then Mojang's own runtime downloaded
 * into a "java-runtimes" folder next to the app.
 */
public final class JavaRuntime {
    private static final String ALL_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private static final String PLATFORM = "windows-x64";

    private JavaRuntime() {}

    public static Path resolve(Config cfg, JsonObject v, Consumer<String> status,
                               BiConsumer<Integer, Integer> progress) throws Exception {
        String component = "jre-legacy";
        int major = 8;
        if (v.has("javaVersion")) {
            JsonObject jv = v.getAsJsonObject("javaVersion");
            if (jv.has("component")) component = jv.get("component").getAsString();
            if (jv.has("majorVersion")) major = jv.get("majorVersion").getAsInt();
        }
        for (String p : cfg.customJavas) {
            Path root = Path.of(p);
            Path exe = exeOf(root);
            if (exe != null && majorOf(root) == major) {
                Log.info("Using imported Java " + major + ": " + root);
                return exe;
            }
        }
        return ensure(cfg, component, status, progress);
    }

    /** bin/javaw.exe (or bin/java.exe) inside a Java home, or null. */
    public static Path exeOf(Path root) {
        Path w = root.resolve("bin").resolve("javaw.exe");
        if (Files.exists(w)) return w;
        Path j = root.resolve("bin").resolve("java.exe");
        return Files.exists(j) ? j : null;
    }

    /** Reads JAVA_VERSION from the "release" file of a Java home. Returns -1 if unknown. */
    public static int majorOf(Path root) {
        try {
            Path rel = root.resolve("release");
            if (!Files.exists(rel)) return -1;
            for (String line : Files.readAllLines(rel, StandardCharsets.UTF_8)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String s = line.substring(13).replace("\"", "").trim();
                    String[] parts = s.split("[._+\\-]");
                    if (parts.length == 0) return -1;
                    int first = Integer.parseInt(parts[0]);
                    if (first == 1 && parts.length > 1) return Integer.parseInt(parts[1]);
                    return first;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static Path appDir() {
        String p = System.getProperty("jpackage.app-path");
        if (p != null) {
            Path parent = Path.of(p).getParent();
            if (parent != null) return parent;
        }
        return Path.of("").toAbsolutePath();
    }

    /** "java-runtimes" next to the app; falls back to .minecraft/vt-launcher if that is not writable. */
    public static Path baseDir(Config cfg) {
        Path pref = appDir().resolve("java-runtimes");
        try {
            Files.createDirectories(pref);
            if (Files.isWritable(pref)) return pref;
        } catch (IOException ignored) {
        }
        return cfg.vtDir().resolve("java-runtimes");
    }

    private static Path ensure(Config cfg, String component, Consumer<String> status,
                               BiConsumer<Integer, Integer> progress) throws Exception {
        Path dir = baseDir(cfg).resolve(component);
        Path exe = dir.resolve("bin").resolve("javaw.exe");
        Path ok = dir.resolve(".vt-ok");
        if (Files.exists(ok) && Files.exists(exe)) return exe;

        status.accept("Getting Java (" + component + ")...");
        byte[] all = Net.getBytes(ALL_URL);
        JsonObject root = JsonParser.parseString(new String(all, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject platform = root.getAsJsonObject(PLATFORM);
        if (platform == null || !platform.has(component) || platform.getAsJsonArray(component).isEmpty()) {
            throw new IOException("Mojang has no Java runtime '" + component + "' for " + PLATFORM
                    + ". Import a local Java from Settings.");
        }
        JsonArray list = platform.getAsJsonArray(component);
        String manifestUrl = list.get(0).getAsJsonObject().getAsJsonObject("manifest").get("url").getAsString();
        byte[] mb = Net.getBytes(manifestUrl);
        JsonObject files = JsonParser.parseString(new String(mb, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("files");

        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            JsonObject f = e.getValue().getAsJsonObject();
            String type = f.get("type").getAsString();
            Path target = dir.resolve(e.getKey());
            if (type.equals("directory")) {
                Files.createDirectories(target);
            } else if (type.equals("file")) {
                JsonObject raw = f.getAsJsonObject("downloads").getAsJsonObject("raw");
                jobs.add(new Job(raw.get("url").getAsString(), target, raw.get("sha1").getAsString()));
            }
        }
        status.accept("Downloading Java (" + jobs.size() + " files)...");
        List<String> failed = Downloader.run(jobs, progress);
        if (!failed.isEmpty()) {
            throw new IOException("Java download failed (" + failed.size() + " files). First: " + failed.get(0));
        }
        if (!Files.exists(exe)) throw new IOException("javaw.exe not found in downloaded Java");
        Files.writeString(ok, "ok");
        Log.info("Java runtime ready: " + component);
        return exe;
    }
}
