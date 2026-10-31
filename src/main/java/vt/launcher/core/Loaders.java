package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Installs mod loaders: Fabric and Quilt (profile json), Forge and NeoForge (official installer), OptiFine. */
public final class Loaders {
    public enum Kind {
        FABRIC, QUILT, FORGE, NEOFORGE, OPTIFINE;

        public String display() {
            return switch (this) {
                case FABRIC -> "Fabric";
                case QUILT -> "Quilt";
                case FORGE -> "Forge";
                case NEOFORGE -> "NeoForge";
                case OPTIFINE -> "OptiFine";
            };
        }
    }

    public record Item(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";
    private static final String FORGE_META = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String FORGE_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String NEO_META = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String NEO_BASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    private static final String OPTIFINE_LIST = "https://bmclapi2.bangbang93.com/optifine/versionList";
    private static final String OPTIFINE_DL = "https://bmclapi2.bangbang93.com/optifine/";

    private static List<String> forgeAll;
    private static List<String> neoAll;
    private static JsonArray optifineAll;

    private Loaders() {}

    // =============== version lists ===============
    public static List<Item> versions(Kind k, String mc) throws Exception {
        return switch (k) {
            case FABRIC -> metaLoaders(FABRIC_META, mc);
            case QUILT -> metaLoaders(QUILT_META, mc);
            case FORGE -> forge(mc);
            case NEOFORGE -> neo(mc);
            case OPTIFINE -> optifine(mc);
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<Item> metaLoaders(String base, String mc) throws Exception {
        byte[] b = Net.getBytes(base + "/versions/loader/" + enc(mc));
        JsonArray arr = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonArray();
        List<Item> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject loader = e.getAsJsonObject().getAsJsonObject("loader");
            String v = loader.get("version").getAsString();
            boolean stable = !loader.has("stable") || loader.get("stable").getAsBoolean();
            out.add(new Item(v, stable ? v : v + "  (unstable)"));
        }
        return out;
    }

    private static synchronized List<String> mavenVersions(String url, boolean forge) throws Exception {
        List<String> cached = forge ? forgeAll : neoAll;
        if (cached != null) return cached;
        byte[] b = Net.getBytes(url);
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(b));
        NodeList nl = doc.getElementsByTagName("version");
        List<String> all = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) all.add(nl.item(i).getTextContent().trim());
        if (forge) forgeAll = all;
        else neoAll = all;
        return all;
    }

    private static List<Item> forge(String mc) throws Exception {
        List<String> all = mavenVersions(FORGE_META, true);
        String prefix = mc + "-";
        List<Item> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) { // newest first
            String v = all.get(i);
            if (v.startsWith(prefix)) out.add(new Item(v, v.substring(prefix.length())));
        }
        return out;
    }

    /** 1.21.1 -> "21.1." ; 1.21 -> "21.0." ; year-style versions like 26.1 -> "26.1." */
    static String neoPrefix(String mc) {
        if (mc.startsWith("1.")) {
            String[] p = mc.substring(2).split("\\.");
            return p[0] + "." + (p.length > 1 ? p[1] : "0") + ".";
        }
        return mc + ".";
    }

    private static List<Item> neo(String mc) throws Exception {
        List<String> all = mavenVersions(NEO_META, false);
        String prefix = neoPrefix(mc);
        List<Item> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            String v = all.get(i);
            if (v.startsWith(prefix)) out.add(new Item(v, v));
        }
        return out;
    }

    private static synchronized JsonArray optifineList() throws Exception {
        if (optifineAll == null) {
            byte[] b = Net.getBytes(OPTIFINE_LIST);
            optifineAll = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        return optifineAll;
    }

    private static List<Item> optifine(String mc) throws Exception {
        List<Item> out = new ArrayList<>();
        for (JsonElement e : optifineList()) {
            JsonObject o = e.getAsJsonObject();
            if (!o.get("mcversion").getAsString().equals(mc)) continue;
            String type = o.get("type").getAsString();
            String patch = o.get("patch").getAsString();
            String file = o.get("filename").getAsString();
            out.add(new Item(type + "|" + patch + "|" + file, file.replace(".jar", "")));
        }
        return out;
    }

    // =============== install ===============
    /** Installs the loader and returns the id of the new version. */
    public static String install(Kind k, String mc, String value, Config cfg,
                                 Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        Path root = cfg.gameDirPath();
        prepareVanilla(mc, root, status, progress);
        return switch (k) {
            case FABRIC -> fromProfile(FABRIC_META, mc, value, root, status, progress);
            case QUILT -> fromProfile(QUILT_META, mc, value, root, status, progress);
            case FORGE -> {
                String ver = value.startsWith(mc + "-") ? value.substring(mc.length() + 1) : value;
                yield viaInstaller(k, mc, FORGE_BASE + value + "/forge-" + value + "-installer.jar",
                        List.of(mc + "-forge-" + ver, mc + "-forge" + mc + "-" + ver), cfg, status, progress);
            }
            case NEOFORGE -> viaInstaller(k, mc, NEO_BASE + value + "/neoforge-" + value + "-installer.jar",
                    List.of("neoforge-" + value), cfg, status, progress);
            case OPTIFINE -> viaOptiFine(mc, value, cfg, status, progress);
        };
    }

    private static void prepareVanilla(String mc, Path root, Consumer<String> status,
                                       BiConsumer<Integer, Integer> progress) throws Exception {
        Path json = root.resolve("versions").resolve(mc).resolve(mc + ".json");
        if (!Files.exists(json)) {
            status.accept("Installing Minecraft " + mc + "...");
            boolean found = false;
            for (Manifest.VersionEntry v : Manifest.load()) {
                if (v.id().equals(mc)) {
                    VersionInstaller.install(mc, v.url(), root, status, progress);
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("Minecraft " + mc + " was not found on Mojang's list.");
        } else {
            VersionInstaller.ensureFiles(mc, root, status, progress);
        }
    }

    // ---- Fabric / Quilt ----
    private static String fromProfile(String base, String mc, String loader, Path root, Consumer<String> status,
                                      BiConsumer<Integer, Integer> progress) throws Exception {
        status.accept("Getting the profile...");
        byte[] pj = Net.getBytes(base + "/versions/loader/" + enc(mc) + "/" + enc(loader) + "/profile/json");
        JsonObject p = JsonParser.parseString(new String(pj, StandardCharsets.UTF_8)).getAsJsonObject();
        String id = p.get("id").getAsString();
        String unique = VersionManager.uniqueId(root, id);
        if (!unique.equals(id)) { // a version with this name exists: add a new one, "name (1)"
            id = unique;
            p.addProperty("id", id);
            pj = p.toString().getBytes(StandardCharsets.UTF_8);
        }
        Path vdir = root.resolve("versions").resolve(id);
        Files.createDirectories(vdir);
        Files.write(vdir.resolve(id + ".json"), pj);
        try {
            VersionInstaller.ensureFiles(id, root, status, progress);
        } catch (Exception e) {
            deleteDir(vdir); // do not leave a half-installed version in the list
            throw e;
        }
        return id;
    }

    // ---- Forge / NeoForge (official installer, run without a window) ----
    private static String viaInstaller(Kind k, String mc, String url, List<String> likelyIds, Config cfg,
                                       Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        Path root = cfg.gameDirPath();
        ensureLauncherProfiles(root);
        String name = k.display();

        Path dl = cfg.vtDir().resolve("downloads");
        Files.createDirectories(dl);
        Path jar = dl.resolve(url.substring(url.lastIndexOf('/') + 1));
        status.accept("Downloading the " + name + " installer...");
        Net.download(url, jar);
        verifyOptionalSha1(url + ".sha1", jar);

        Path java = javaFor(cfg, mc, status, progress, true);
        Path log = installerLog(cfg, name);

        // the installer writes a fixed folder name; park an existing one so it is not replaced
        Map<String, Path> parked = park(root, likelyIds);
        String id;
        try {
            Set<String> before = new HashSet<>(Manifest.installed(root));
            status.accept("Running the " + name + " installer (this can take several minutes)...");
            progress.accept(0, 0);
            int code = run(List.of(java.toString(), "-jar", jar.toString(), "--installClient", root.toString()),
                    cfg.vtDir(), log, 40);
            if (code != 0) {
                throw new IOException(name + " installer failed (code " + code + "). Log: " + log + "\n" + tail(log));
            }
            id = newVersion(root, before, mc, name, log);
            if (parked.containsKey(id)) id = moveToUnique(root, id);
        } finally {
            unpark(root, parked);
        }
        VersionInstaller.ensureFiles(id, root, status, progress);
        return id;
    }

    // ---- OptiFine (its own installer window; there is no silent mode) ----
    private static String viaOptiFine(String mc, String value, Config cfg, Consumer<String> status,
                                      BiConsumer<Integer, Integer> progress) throws Exception {
        Path root = cfg.gameDirPath();
        ensureLauncherProfiles(root);
        Path jar = downloadOptiFine(mc, value, cfg, status);
        Path exe = javaFor(cfg, mc, status, progress, false);
        String[] p = value.split("\\|", 3);

        Map<String, Path> parked = park(root, List.of(mc + "-OptiFine_" + p[0] + "_" + p[1]));
        String id;
        try {
            Set<String> before = new HashSet<>(Manifest.installed(root));
            status.accept("OptiFine installer opened. Check the folder says " + root + " , press Install, then close it.");
            progress.accept(0, 0);
            Process pr = new ProcessBuilder(exe.toString(), "-jar", jar.toString())
                    .directory(cfg.vtDir().toFile()).start();
            if (!pr.waitFor(20, TimeUnit.MINUTES)) {
                pr.destroyForcibly();
                throw new IOException("OptiFine installer was left open too long.");
            }
            id = newVersion(root, before, mc, "OptiFine", null);
            if (parked.containsKey(id)) id = moveToUnique(root, id);
        } finally {
            unpark(root, parked);
        }
        VersionInstaller.ensureFiles(id, root, status, progress);
        return id;
    }

    private static Map<String, Path> park(Path root, List<String> ids) throws IOException {
        Map<String, Path> parked = new HashMap<>();
        Path versions = root.resolve("versions");
        for (String id : ids) {
            Path d = versions.resolve(id);
            if (Files.isDirectory(d)) {
                Path to = versions.resolve(id + ".vt-old");
                deleteDir(to);
                Files.move(d, to);
                parked.put(id, to);
            }
        }
        return parked;
    }

    private static void unpark(Path root, Map<String, Path> parked) {
        Path versions = root.resolve("versions");
        for (Map.Entry<String, Path> e : parked.entrySet()) {
            try {
                Path orig = versions.resolve(e.getKey());
                if (Files.exists(orig)) deleteDir(orig); // leftover of a failed install
                Files.move(e.getValue(), orig);
            } catch (IOException ex) {
                Log.error("Could not restore " + e.getKey(), ex);
            }
        }
    }

    /** The new version took the name of an existing one: give it "name (1)" instead. */
    private static String moveToUnique(Path root, String id) throws IOException {
        String neu = VersionManager.uniqueId(root, id);
        VersionManager.relocate(root, id, neu);
        return neu;
    }

    /** Downloads the OptiFine jar (BMCLAPI mirror). value = type|patch|filename. */
    public static Path downloadOptiFine(String mc, String value, Config cfg, Consumer<String> status) throws Exception {
        String[] p = value.split("\\|", 3);
        Path dl = cfg.vtDir().resolve("downloads");
        Files.createDirectories(dl);
        Path jar = dl.resolve(p[2]);
        if (Files.exists(jar) && Files.size(jar) > 500_000) return jar;
        status.accept("Downloading " + p[2] + "...");
        Net.download(OPTIFINE_DL + enc(mc) + "/" + enc(p[0]) + "/" + enc(p[1]), jar);
        if (Files.size(jar) < 500_000) {
            Files.deleteIfExists(jar);
            throw new IOException("The OptiFine download looks wrong (too small).");
        }
        return jar;
    }

    // =============== helpers ===============
    private static void ensureLauncherProfiles(Path root) throws IOException {
        Files.createDirectories(root);
        Path f = root.resolve("launcher_profiles.json");
        if (!Files.exists(f)) {
            Files.writeString(f, "{\"profiles\":{},\"settings\":{},\"version\":3}");
        }
    }

    /** Java that matches the Minecraft version. console=true prefers java.exe (so output can be captured). */
    private static Path javaFor(Config cfg, String mc, Consumer<String> status, BiConsumer<Integer, Integer> progress,
                                boolean console) throws Exception {
        JsonObject v = VersionJson.load(cfg.gameDirPath(), mc);
        Path exe = JavaRuntime.resolve(cfg, v, status, progress);
        if (console) {
            Path j = exe.resolveSibling("java.exe");
            if (Files.exists(j)) return j;
        }
        return exe;
    }

    private static Path installerLog(Config cfg, String name) throws IOException {
        Path dir = cfg.vtDir().resolve("logs");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return dir.resolve("installer-" + name + "-" + stamp + ".log");
    }

    private static int run(List<String> cmd, Path dir, Path log, int minutes) throws Exception {
        Files.createDirectories(dir);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("The installer took longer than " + minutes + " minutes. Log: " + log);
        }
        return p.exitValue();
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 8);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /** The loader creates its own version folder; find it by comparing before/after. */
    private static String newVersion(Path root, Set<String> before, String mc, String name, Path log) throws IOException {
        List<String> fresh = new ArrayList<>();
        for (String id : Manifest.installed(root)) if (!before.contains(id)) fresh.add(id);
        if (fresh.isEmpty()) {
            throw new IOException(name + " finished but no new version appeared in " + root.resolve("versions")
                    + (log != null ? ". Log: " + log : ". Was the folder in the installer the same as the game folder?"));
        }
        for (String id : fresh) if (id.contains(mc)) return id;
        return fresh.get(0);
    }

    private static void verifyOptionalSha1(String url, Path file) {
        try {
            String want = new String(Net.getBytes(url), StandardCharsets.UTF_8).trim().split("\\s+")[0];
            if (want.length() == 40 && !Downloader.sha1(file).equalsIgnoreCase(want)) {
                Files.deleteIfExists(file);
                throw new IOException("The downloaded installer is corrupted (SHA1 does not match). Try again.");
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("corrupted")) throw new IllegalStateException(e.getMessage());
            Log.warn("Could not verify installer SHA1: " + e.getMessage());
        } catch (Exception e) {
            Log.warn("Could not verify installer SHA1: " + e.getMessage());
        }
    }

    private static void deleteDir(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
