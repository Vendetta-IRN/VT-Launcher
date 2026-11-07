package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vt.launcher.core.Downloader.Job;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds or downloads the Java a Minecraft version needs.
 * Order: Java you imported by hand, then every Java that is already installed on this PC (detected automatically),
 * then Mojang's own runtime in a "java-runtimes" folder next to the app (downloaded once if needed).
 */
public final class JavaRuntime {
    private static final String ALL_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private static final String PLATFORM = "windows-x64";

    /** An installed Java. source = where it was found (imported, JAVA_HOME, PATH, folder name, registry...). */
    public record Found(Path home, Path exe, int major, String vendor, String source) {
        @Override
        public String toString() {
            return "Java " + major + (vendor.isEmpty() ? "" : "  -  " + vendor) + "   (" + home + ")";
        }
    }

    /** What resolve() picked, so the launcher can tell the user which Java is used. */
    public record Choice(Path exe, int major, String origin) {}

    private static volatile List<Found> cache;

    private JavaRuntime() {}

    // =============== which Java does a version need ===============
    public static int requiredMajor(JsonObject v) {
        if (v.has("javaVersion")) {
            JsonObject jv = v.getAsJsonObject("javaVersion");
            if (jv.has("majorVersion")) return jv.get("majorVersion").getAsInt();
        }
        return 8;
    }

    private static String componentOf(JsonObject v) {
        if (v.has("javaVersion")) {
            JsonObject jv = v.getAsJsonObject("javaVersion");
            if (jv.has("component")) return jv.get("component").getAsString();
        }
        return "jre-legacy";
    }

    // =============== resolve ===============
    public static Path resolve(Config cfg, JsonObject v, Consumer<String> status,
                               BiConsumer<Integer, Integer> progress) throws Exception {
        return choose(cfg, v, status, progress).exe();
    }

    public static Choice choose(Config cfg, JsonObject v, Consumer<String> status,
                                BiConsumer<Integer, Integer> progress) throws Exception {
        String component = componentOf(v);
        int major = requiredMajor(v);

        // 1. a Java the user imported by hand
        for (String p : cfg.customJavas) {
            Path root = Path.of(p);
            Path exe = exeOf(root);
            if (exe != null && majorOf(root) == major) {
                Log.info("Using imported Java " + major + ": " + root);
                return new Choice(exe, major, "imported Java " + major);
            }
        }

        // 2. a Java that is already installed on this PC with exactly the right version
        status.accept("Looking for Java " + major + "...");
        List<Found> installed = detect(false);
        for (Found f : installed) {
            if (f.major() == major) {
                Log.info("Using installed Java " + major + ": " + f.home() + " (" + f.source() + ")");
                return new Choice(f.exe(), major, "installed Java " + major + " (" + f.home() + ")");
            }
        }

        // 3. Mojang's runtime, already downloaded earlier
        Path cached = downloaded(cfg, component);
        if (cached != null) return new Choice(cached, major, "Mojang runtime " + component);

        // 4. download Mojang's runtime (the right version, once)
        try {
            Path exe = ensure(cfg, component, status, progress);
            return new Choice(exe, major, "downloaded Java " + major);
        } catch (Exception e) {
            Log.warn("Java download failed: " + e.getMessage());
            // 5. last resort: a newer installed Java. Fine for 1.17+ (Java 16 and up), not for old versions.
            if (major >= 16) {
                Found best = null;
                for (Found f : installed) {
                    if (f.major() > major && (best == null || f.major() < best.major())) best = f;
                }
                if (best != null) {
                    Log.info("Using newer installed Java " + best.major() + " instead of " + major + ": " + best.home());
                    status.accept("Java " + major + " could not be downloaded, using Java " + best.major() + " instead...");
                    return new Choice(best.exe(), best.major(), "installed Java " + best.major() + " (newer than needed)");
                }
            }
            throw new IOException("This version needs Java " + major + ", and none was found on this PC.\n"
                    + "Downloading it also failed: " + e.getMessage() + "\n\n"
                    + "Fix: connect to the internet (or tick the mirror option in Settings) and press PLAY again, "
                    + "or install Java " + major + " (for example Temurin " + major + " from adoptium.net). "
                    + "The launcher finds installed Java automatically; nothing has to be added by hand.", e);
        }
    }

    // =============== detection of installed Java ===============
    /** All Java installations found on this PC, newest first. The result is remembered; refresh=true scans again. */
    public static synchronized List<Found> detect(boolean refresh) {
        if (cache != null && !refresh) return cache;
        long t0 = System.currentTimeMillis();
        Map<String, Found> out = new LinkedHashMap<>();

        // JAVA_HOME and friends
        for (String env : new String[]{"JAVA_HOME", "JDK_HOME", "JRE_HOME"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) tryHome(out, Path.of(v.trim().replace("\"", "")), env);
        }

        // PATH: every folder that holds java.exe
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                try {
                    Path d = Path.of(dir.trim().replace("\"", ""));
                    Path exe = d.resolve("java.exe");
                    if (!Files.exists(exe)) exe = d.resolve("java");
                    if (!Files.exists(exe)) continue;
                    // C:\Program Files\Common Files\Oracle\Java\javapath only holds links: follow them to the real Java
                    Path real = exe.toRealPath();
                    Path bin = real.getParent();
                    if (bin != null && bin.getParent() != null) tryHome(out, bin.getParent(), "PATH");
                } catch (Exception ignored) {
                }
            }
        }

        // the usual install folders
        for (Path base : scanRoots()) scan(out, base, 3);

        // the Windows registry (installers of Oracle, Adoptium, Zulu, Corretto, Microsoft... register themselves)
        if (isWindows()) {
            for (String key : new String[]{
                    "HKLM\\SOFTWARE\\JavaSoft\\JDK", "HKLM\\SOFTWARE\\JavaSoft\\JRE",
                    "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit", "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
                    "HKLM\\SOFTWARE\\Eclipse Adoptium", "HKLM\\SOFTWARE\\Eclipse Foundation",
                    "HKLM\\SOFTWARE\\AdoptOpenJDK", "HKLM\\SOFTWARE\\Microsoft\\JDK",
                    "HKLM\\SOFTWARE\\Azul Systems\\Zulu", "HKLM\\SOFTWARE\\Amazon Corretto",
                    "HKLM\\SOFTWARE\\BellSoft", "HKLM\\SOFTWARE\\Semeru",
                    "HKCU\\SOFTWARE\\JavaSoft\\JDK", "HKCU\\SOFTWARE\\JavaSoft\\JRE"}) {
                for (Path home : registryHomes(key)) tryHome(out, home, "registry");
            }
        }

        List<Found> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingInt(Found::major).reversed());
        cache = list;
        Log.info("Java detection: " + list.size() + " found in " + (System.currentTimeMillis() - t0) + " ms");
        for (Found f : list) Log.info("  " + f + " [" + f.source() + "]");
        return list;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<Path> scanRoots() {
        List<Path> r = new ArrayList<>();
        String[] vendorDirs = {"Java", "Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK", "Microsoft", "Zulu",
                "Azul Systems", "BellSoft", "Amazon Corretto", "Semeru", "IBM", "OpenJDK", "GraalVM", "RedHat",
                "SapMachine", "Temurin", "Oracle", "Liberica", "JetBrains", "Android"};
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String pf = System.getenv(env);
            if (pf == null || pf.isBlank()) continue;
            for (String v : vendorDirs) r.add(Path.of(pf, v));
            r.add(Path.of(pf, "Minecraft Launcher", "runtime"));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            for (String v : vendorDirs) r.add(Path.of(local, "Programs", v));
            r.add(Path.of(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"));
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) r.add(Path.of(appdata, ".minecraft", "runtime"));
        String home = System.getProperty("user.home");
        if (home != null) {
            r.add(Path.of(home, ".jdks"));              // IntelliJ
            r.add(Path.of(home, ".gradle", "jdks"));    // Gradle toolchains
            r.add(Path.of(home, "scoop", "apps"));
            r.add(Path.of(home, ".sdkman", "candidates", "java"));
        }
        for (String drive : new String[]{"C:\\", "D:\\"}) {
            r.add(Path.of(drive, "Java"));
            r.add(Path.of(drive, "jdk"));
        }
        String sysRoot = System.getenv("SystemDrive");
        if (sysRoot != null && !sysRoot.isBlank()) r.add(Path.of(sysRoot + "\\", "Java"));
        return r;
    }

    /** Looks for a Java home (a folder with bin/java.exe) in base, and up to `depth` folders below it. */
    private static void scan(Map<String, Found> out, Path base, int depth) {
        try {
            if (!Files.isDirectory(base)) return;
            if (exeOf(base) != null) {
                tryHome(out, base, "folder");
                return;
            }
            if (depth <= 0) return;
            try (Stream<Path> s = Files.list(base)) {
                for (Path c : s.filter(Files::isDirectory).toList()) scan(out, c, depth - 1);
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Path> registryHomes(String key) {
        List<Path> homes = new ArrayList<>();
        Pattern line = Pattern.compile("^\\s+(JavaHome|Path|InstallationPath)\\s+REG_SZ\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
        for (String view : new String[]{"/reg:64", "/reg:32"}) {
            try {
                Process p = new ProcessBuilder("reg", "query", key, "/s", view).redirectErrorStream(true).start();
                String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(6, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                for (String l : text.split("\\R")) {
                    Matcher m = line.matcher(l);
                    if (m.matches()) {
                        Path h = Path.of(m.group(2).trim());
                        if (!homes.contains(h)) homes.add(h);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return homes;
    }

    private static void tryHome(Map<String, Found> out, Path home, String source) {
        try {
            if (home == null || !Files.isDirectory(home)) return;
            Path exe = exeOf(home);
            if (exe == null) return;
            String id = home.toRealPath().toString().toLowerCase(Locale.ROOT);
            if (out.containsKey(id)) return;

            String arch = releaseValue(home, "OS_ARCH");
            if (arch != null && (arch.equals("x86") || arch.equals("i586") || arch.equals("i386") || arch.equals("i686"))) {
                return; // 32-bit Java cannot give the game more than ~1.5 GB
            }
            int major = majorOf(home);
            if (major < 0) major = askJava(home);
            if (major < 6) return;
            String vendor = releaseValue(home, "IMPLEMENTOR");
            if (vendor == null) vendor = "";
            out.put(id, new Found(home, exe, major, vendor, source));
        } catch (Exception ignored) {
        }
    }

    /** Runs "java -version" for a Java without a release file. Returns -1 if that does not work. */
    private static int askJava(Path home) {
        try {
            Path j = home.resolve("bin").resolve("java.exe");
            if (!Files.exists(j)) j = home.resolve("bin").resolve("java");
            Process p = new ProcessBuilder(j.toString(), "-version").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(6, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            Matcher m = Pattern.compile("version \"([0-9._+\\-a-zA-Z]+)\"").matcher(text);
            if (m.find()) return parseMajor(m.group(1));
        } catch (Exception ignored) {
        }
        return -1;
    }

    // =============== helpers ===============
    /** bin/javaw.exe (or bin/java.exe) inside a Java home, or null. */
    public static Path exeOf(Path root) {
        for (String n : new String[]{"javaw.exe", "java.exe", "java"}) {
            Path p = root.resolve("bin").resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String releaseValue(Path root, String key) {
        try {
            Path rel = root.resolve("release");
            if (!Files.exists(rel)) return null;
            for (String line : Files.readAllLines(rel, StandardCharsets.UTF_8)) {
                if (line.startsWith(key + "=")) return line.substring(key.length() + 1).replace("\"", "").trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static int parseMajor(String s) {
        try {
            String[] parts = s.split("[._+\\-]");
            if (parts.length == 0) return -1;
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) return Integer.parseInt(parts[1]);
            return first;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads JAVA_VERSION from the "release" file of a Java home. Returns -1 if unknown. */
    public static int majorOf(Path root) {
        String v = releaseValue(root, "JAVA_VERSION");
        return v == null ? -1 : parseMajor(v);
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

    private static Path downloaded(Config cfg, String component) {
        for (Path base : new Path[]{baseDir(cfg), cfg.vtDir().resolve("java-runtimes")}) {
            Path dir = base.resolve(component);
            Path exe = dir.resolve("bin").resolve("javaw.exe");
            if (Files.exists(dir.resolve(".vt-ok")) && Files.exists(exe)) return exe;
        }
        return null;
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
            throw new IOException("Mojang has no Java runtime '" + component + "' for " + PLATFORM + ".");
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
        status.accept("Downloading Java " + component + " (" + jobs.size() + " files, one time only)...");
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
