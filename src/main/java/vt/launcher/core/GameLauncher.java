package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Builds the java command for an installed version and starts the game (offline account). */
public final class GameLauncher {
    public record Started(Process process, Path log, boolean skin) {}

    private GameLauncher() {}

    public static Started launch(Config cfg, Profile prof, Consumer<String> status,
                                 BiConsumer<Integer, Integer> progress) throws Exception {
        String id = prof.version;
        Path gameDir = cfg.gameDirPath();                // shared: versions, libraries, assets
        Path instance = cfg.gameDirFor(id);              // per version when isolated: saves, mods, options...
        Files.createDirectories(instance);
        status.accept("Reading version...");
        JsonObject v = VersionJson.load(gameDir, id);

        Path java = JavaRuntime.resolve(cfg, v, status, progress);

        status.accept("Preparing...");
        Path libs = gameDir.resolve("libraries");
        Path assets = gameDir.resolve("assets");
        String clientId = v.get("clientJarId").getAsString();
        Path clientJar = gameDir.resolve("versions").resolve(clientId).resolve(clientId + ".jar");
        Path natives = gameDir.resolve("versions").resolve(id).resolve("natives");
        Files.createDirectories(natives);

        List<Path> cpPaths = classpath(v, libs, clientJar);
        List<String> missing = new ArrayList<>();
        for (Path p : cpPaths) if (!Files.exists(p)) missing.add(p.getFileName().toString());
        if (!missing.isEmpty()) {
            throw new IOException(missing.size() + " files are missing (e.g. " + missing.get(0)
                    + "). Use Repair on the Versions page.");
        }
        String cp = cpPaths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + prof.player).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        JsonObject ai = v.getAsJsonObject("assetIndex");
        boolean customRes = cfg.resW > 0 && cfg.resH > 0;

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", prof.player);
        vars.put("version_name", id);
        vars.put("game_directory", instance.toString());
        vars.put("assets_root", assets.toString());
        vars.put("assets_index_name", ai.get("id").getAsString());
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", "0");
        vars.put("auth_session", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", v.has("type") ? v.get("type").getAsString() : "release");
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", "VT-Launcher");
        vars.put("launcher_version", "1.0");
        vars.put("classpath", cp);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", libs.toString());
        vars.put("game_assets", assets.resolve("virtual").resolve("legacy").toString());
        vars.put("resolution_width", String.valueOf(cfg.resW));
        vars.put("resolution_height", String.valueOf(cfg.resH));

        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution", customRes);

        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-Xms" + Math.min(1024, cfg.ramMb) + "M");
        cmd.add("-Xmx" + cfg.ramMb + "M");
        if (cfg.jvmArgs != null && !cfg.jvmArgs.isBlank()) {
            cmd.addAll(Arrays.asList(cfg.jvmArgs.trim().split("\\s+")));
        }

        // local skin / cape through authlib-injector + a tiny local server
        SkinServer skinServer = null;
        if (prof.hasSkin || prof.hasCape) {
            try {
                Path injector = SkinService.ensureInjector(cfg, status);
                byte[] skin = prof.hasSkin && Files.exists(prof.skinFile(cfg)) ? Files.readAllBytes(prof.skinFile(cfg)) : null;
                byte[] cape = prof.hasCape && Files.exists(prof.capeFile(cfg)) ? Files.readAllBytes(prof.capeFile(cfg)) : null;
                if (skin != null || cape != null) {
                    skinServer = new SkinServer(uuid, prof.player, skin, "slim".equals(prof.skinModel), cape);
                    cmd.add("-javaagent:" + injector + "=" + skinServer.url());
                    cmd.add("-Dauthlibinjector.noLogFile=true");
                    cmd.add("-Dauthlibinjector.mojangNamespace=disabled");
                }
            } catch (Exception e) {
                Log.warn("Skin support unavailable: " + e.getMessage());
                status.accept("Skin support unavailable, starting without skin...");
                if (skinServer != null) {
                    skinServer.close();
                    skinServer = null;
                }
            }
        }

        JsonObject args = v.has("arguments") ? v.getAsJsonObject("arguments") : null;
        if (args != null && args.has("jvm")) {
            collect(args.getAsJsonArray("jvm"), cmd, vars, features);
        } else {
            cmd.add("-Djava.library.path=" + natives);
            cmd.add("-cp");
            cmd.add(cp);
        }

        cmd.add(v.get("mainClass").getAsString());

        boolean modernGame = args != null && args.has("game");
        if (modernGame) {
            collect(args.getAsJsonArray("game"), cmd, vars, features);
        } else if (v.has("minecraftArguments")) {
            for (String s : v.get("minecraftArguments").getAsString().trim().split("\\s+")) cmd.add(sub(s, vars));
            if (customRes) {
                cmd.add("--width");
                cmd.add(String.valueOf(cfg.resW));
                cmd.add("--height");
                cmd.add(String.valueOf(cfg.resH));
            }
        }
        if (cfg.fullscreen) cmd.add("--fullscreen");

        Path logDir = cfg.vtDir().resolve("logs");
        Files.createDirectories(logDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path log = logDir.resolve("game-" + id + "-" + stamp + ".log");
        pruneLogs(logDir);

        Log.info("Launching " + id + ": " + String.join(" ", cmd));
        status.accept("Starting game...");
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(instance.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            if (skinServer != null) skinServer.close();
            throw e;
        }
        SkinServer srv = skinServer;
        if (srv != null) p.onExit().thenRun(srv::close);
        return new Started(p, log, srv != null);
    }

    private static List<Path> classpath(JsonObject v, Path libs, Path clientJar) {
        List<Path> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Map<String, Boolean> none = Map.of();
        if (v.has("libraries")) {
            for (JsonElement le : v.getAsJsonArray("libraries")) {
                JsonObject lib = le.getAsJsonObject();
                if (!Rules.allowed(lib.getAsJsonArray("rules"), none)) continue;
                Path p = null;
                if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                    p = libs.resolve(lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString());
                } else if (lib.has("name") && !lib.has("natives")) {
                    p = libs.resolve(mavenPath(lib.get("name").getAsString()));
                }
                if (p != null && seen.add(p)) out.add(p);
            }
        }
        if (seen.add(clientJar)) out.add(clientJar);
        return out;
    }

    /** group:artifact:version[:classifier][@ext] -> relative maven path. */
    static String mavenPath(String name) {
        String ext = "jar";
        String n = name;
        int at = n.indexOf('@');
        if (at >= 0) {
            ext = n.substring(at + 1);
            n = n.substring(0, at);
        }
        String[] p = n.split(":");
        String cls = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cls + "." + ext;
    }

    private static void collect(JsonArray arr, List<String> out, Map<String, String> vars, Map<String, Boolean> features) {
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                out.add(sub(e.getAsString(), vars));
            } else {
                JsonObject o = e.getAsJsonObject();
                if (!Rules.allowed(o.getAsJsonArray("rules"), features)) continue;
                JsonElement val = o.get("value");
                if (val.isJsonArray()) {
                    for (JsonElement x : val.getAsJsonArray()) out.add(sub(x.getAsString(), vars));
                } else {
                    out.add(sub(val.getAsString(), vars));
                }
            }
        }
    }

    private static String sub(String s, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** Keeps only the 20 newest game logs. */
    private static void pruneLogs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> logs = s.filter(p -> p.getFileName().toString().startsWith("game-"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
            for (int i = 20; i < logs.size(); i++) Files.deleteIfExists(logs.get(i));
        } catch (IOException ignored) {
        }
    }
}
