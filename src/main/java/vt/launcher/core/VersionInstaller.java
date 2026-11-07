package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vt.launcher.core.Downloader.Job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads / completes the files of a version (client jar, libraries, assets) in a .minecraft layout.
 * install() = new vanilla version from Mojang. ensureFiles() = complete any installed version
 * (vanilla, Fabric, Forge, local imports...).
 */
public final class VersionInstaller {
    private static final String OS = "windows";

    /** Maven repositories tried for libraries that name no (working) repository. The first is Mojang's own. */
    private static final String[] REPOS = {
            "https://libraries.minecraft.net/",
            "https://maven.quiltmc.org/repository/release/",
            "https://maven.fabricmc.net/",
            "https://repo1.maven.org/maven2/",
            "https://maven.minecraftforge.net/",
            "https://maven.neoforged.net/releases/"
    };

    private record NativeLib(Path jar, List<String> exclude) {}

    private record Plan(List<Job> jobs, List<NativeLib> natives, JsonObject index,
                        List<Path> noUrl) {}

    private VersionInstaller() {}

    /** Installs a vanilla version from its Mojang url. The version json is written last. */
    public static void install(String id, String versionUrl, Path root,
                               Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        installInto(id, id, versionUrl, root, status, progress);
    }

    /** Like install(), but never replaces an existing version: if the name is taken it becomes "name (1)". */
    public static String installAsNew(String mc, String versionUrl, Path root,
                                      Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        String id = VersionManager.uniqueId(root, mc);
        installInto(id, mc, versionUrl, root, status, progress);
        return id;
    }

    private static void installInto(String id, String mc, String versionUrl, Path root,
                                    Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        status.accept("Reading version info...");
        byte[] vbytes = Net.getBytes(versionUrl);
        JsonObject d = JsonParser.parseString(new String(vbytes, StandardCharsets.UTF_8)).getAsJsonObject();
        d.addProperty("clientJarId", id);
        complete(id, d, root, status, progress);

        Path vdir = root.resolve("versions").resolve(id);
        Files.createDirectories(vdir);
        if (!id.equals(mc)) {
            // keep the real Minecraft version, the folder name is different
            JsonObject own = JsonParser.parseString(new String(vbytes, StandardCharsets.UTF_8)).getAsJsonObject();
            own.addProperty("vt_mc_version", mc);
            vbytes = own.toString().getBytes(StandardCharsets.UTF_8);
        }
        Files.write(vdir.resolve(id + ".json"), vbytes);
        Log.info("Installed " + id);
    }

    /** Re-checks (SHA1) and downloads whatever is missing for an already installed version. */
    public static void ensureFiles(String id, Path root,
                                   Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        status.accept("Reading version info...");
        JsonObject d = VersionJson.load(root, id);
        complete(id, d, root, status, progress);
        Log.info("Verified " + id);
    }

    /** Relative library paths a version json refers to (artifacts and native classifiers, any OS). */
    public static List<String> libraryPaths(JsonObject d) {
        List<String> out = new ArrayList<>();
        if (!d.has("libraries")) return out;
        for (JsonElement le : d.getAsJsonArray("libraries")) {
            JsonObject lib = le.getAsJsonObject();
            JsonObject dl = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            if (dl != null && dl.has("artifact")) {
                out.add(dl.getAsJsonObject("artifact").get("path").getAsString());
            } else if (lib.has("name") && !lib.has("natives")) {
                out.add(GameLauncher.mavenPath(lib.get("name").getAsString()));
            }
            if (dl != null && dl.has("classifiers")) {
                for (Map.Entry<String, JsonElement> c : dl.getAsJsonObject("classifiers").entrySet()) {
                    out.add(c.getValue().getAsJsonObject().get("path").getAsString());
                }
            }
        }
        return out;
    }

    // ---------- internals ----------
    private static void complete(String id, JsonObject d, Path root,
                                 Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        Plan plan = plan(id, d, root, status);
        status.accept("Downloading " + plan.jobs().size() + " files...");
        List<String> failed = Downloader.run(plan.jobs(), progress);
        if (!failed.isEmpty()) {
            Log.warn(failed.size() + " files failed: " + failed);
            throw new IOException(failed.size() + " files failed (run Repair to retry). First: " + failed.get(0));
        }
        for (Path p : plan.noUrl()) {
            if (!Files.exists(p)) {
                throw new IOException("Missing library " + p.getFileName() + " and there is no download link for it. "
                        + "Import this version from the original .minecraft folder (the one with its libraries).");
            }
        }

        status.accept("Finishing...");
        Path ndir = root.resolve("versions").resolve(id).resolve("natives").toAbsolutePath().normalize();
        for (NativeLib n : plan.natives()) extract(n, ndir);

        JsonObject idx = plan.index();
        if (idx != null) {
            boolean legacy = (idx.has("virtual") && idx.get("virtual").getAsBoolean())
                    || (idx.has("map_to_resources") && idx.get("map_to_resources").getAsBoolean());
            if (legacy) {
                for (Map.Entry<String, JsonElement> e : idx.getAsJsonObject("objects").entrySet()) {
                    String h = e.getValue().getAsJsonObject().get("hash").getAsString();
                    Path src = root.resolve("assets").resolve("objects").resolve(h.substring(0, 2)).resolve(h);
                    Path dst = root.resolve("assets").resolve("virtual").resolve("legacy").resolve(e.getKey());
                    if (!Files.exists(dst)) {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst);
                    }
                }
            }
        }
    }

    private static Plan plan(String id, JsonObject d, Path root, Consumer<String> status) throws Exception {
        List<Job> jobs = new ArrayList<>();
        List<NativeLib> natives = new ArrayList<>();
        List<Path> noUrl = new ArrayList<>();
        Path libs = root.resolve("libraries");

        // client jar (of the vanilla parent for modded versions)
        String jarId = d.has("clientJarId") ? d.get("clientJarId").getAsString() : id;
        if (d.has("downloads") && d.getAsJsonObject("downloads").has("client")) {
            JsonObject c = d.getAsJsonObject("downloads").getAsJsonObject("client");
            jobs.add(new Job(c.get("url").getAsString(),
                    root.resolve("versions").resolve(jarId).resolve(jarId + ".jar"), c.get("sha1").getAsString()));
        }

        // libraries
        if (d.has("libraries")) {
            for (JsonElement le : d.getAsJsonArray("libraries")) {
                JsonObject lib = le.getAsJsonObject();
                if (!Rules.allowed(lib.getAsJsonArray("rules"), Map.of())) continue;
                JsonObject dl = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;

                if (dl != null && dl.has("artifact")) {
                    JsonObject a = dl.getAsJsonObject("artifact");
                    Path target = libs.resolve(a.get("path").getAsString());
                    String url = a.has("url") ? a.get("url").getAsString() : "";
                    String sha = a.has("sha1") ? a.get("sha1").getAsString() : "";
                    if (url.isEmpty()) noUrl.add(target);
                    else jobs.add(new Job(url, target, sha));
                } else if (lib.has("name") && !lib.has("natives")) {
                    String name = lib.get("name").getAsString();
                    String rel = GameLauncher.mavenPath(name);
                    // Fabric/Quilt profiles name a repository per library, but some entries have none (or an empty
                    // one). Those used to be looked up only on Mojang's server, which does not host Quilt/Fabric
                    // libraries, so the whole install failed. Now every known repository is tried in turn.
                    String base = lib.has("url") && !lib.get("url").getAsString().isBlank()
                            ? lib.get("url").getAsString() : REPOS[0];
                    if (!base.endsWith("/")) base += "/";
                    String sha = lib.has("sha1") ? lib.get("sha1").getAsString() : "";
                    List<String> fallbacks = new ArrayList<>();
                    for (String r : REPOS) if (!r.equals(base)) fallbacks.add(r + rel);
                    jobs.add(new Job(base + rel, libs.resolve(rel), sha, fallbacks));
                }

                if (lib.has("natives") && lib.getAsJsonObject("natives").has(OS) && dl != null && dl.has("classifiers")) {
                    String key = lib.getAsJsonObject("natives").get(OS).getAsString().replace("${arch}", "64");
                    JsonObject cls = dl.getAsJsonObject("classifiers");
                    if (cls.has(key)) {
                        JsonObject a = cls.getAsJsonObject(key);
                        Path p = libs.resolve(a.get("path").getAsString());
                        jobs.add(new Job(a.get("url").getAsString(), p, a.get("sha1").getAsString()));
                        List<String> excl = new ArrayList<>();
                        if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude")) {
                            for (JsonElement x : lib.getAsJsonObject("extract").getAsJsonArray("exclude")) excl.add(x.getAsString());
                        }
                        natives.add(new NativeLib(p, excl));
                    }
                }
            }
        }

        // asset index + assets
        JsonObject idx = null;
        if (d.has("assetIndex")) {
            status.accept("Reading asset index...");
            JsonObject ai = d.getAsJsonObject("assetIndex");
            Path idxPath = root.resolve("assets").resolve("indexes").resolve(ai.get("id").getAsString() + ".json");
            Downloader.fetch(new Job(ai.get("url").getAsString(), idxPath, ai.get("sha1").getAsString()));
            idx = JsonParser.parseString(Files.readString(idxPath, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, JsonElement> e : idx.getAsJsonObject("objects").entrySet()) {
                String h = e.getValue().getAsJsonObject().get("hash").getAsString();
                if (!seen.add(h)) continue;
                String h2 = h.substring(0, 2);
                jobs.add(new Job("https://resources.download.minecraft.net/" + h2 + "/" + h,
                        root.resolve("assets").resolve("objects").resolve(h2).resolve(h), h));
            }
        }
        return new Plan(jobs, natives, idx, noUrl);
    }

    private static void extract(NativeLib n, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(n.jar()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || n.exclude().stream().anyMatch(name::startsWith)) continue;
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
