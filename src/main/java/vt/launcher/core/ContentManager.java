package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local management of a game folder's mods, resource packs, shader packs and worlds.
 * Disabled mods/packs end with ".disabled".
 */
public final class ContentManager {
    public enum Type {
        MODS("mods", ".jar"),
        RESOURCEPACKS("resourcepacks", ".zip"),
        SHADERS("shaderpacks", ".zip"),
        WORLDS("saves", ".zip");

        public final String folder;
        public final String ext;

        Type(String folder, String ext) {
            this.folder = folder;
            this.ext = ext;
        }

        /** Worlds cannot be switched off, everything else can. */
        public boolean toggles() {
            return this != WORLDS;
        }
    }

    /** What an installed version runs: its Minecraft version and loaders (empty = vanilla). */
    public record Env(String mc, List<String> loaders) {}

    public record Entry(Path path, String fileName, String display, String info, boolean enabled, long size, boolean dir) {}

    private static final String OFF = ".disabled";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");

    private ContentManager() {}

    public static Path dir(Path gameDir, Type t) {
        return gameDir.resolve(t.folder);
    }

    /** Minecraft version and loaders of an installed version. */
    public static Env env(Config cfg, String versionId) {
        if (versionId == null || versionId.isBlank()) return new Env(null, List.of());
        try {
            JsonObject v = VersionJson.load(cfg.gameDirPath(), versionId);
            String mc = v.has("vt_mc_version") ? v.get("vt_mc_version").getAsString()
                    : v.has("clientJarId") ? v.get("clientJarId").getAsString() : versionId;
            List<String> tags = VersionType.detect(v);
            List<String> loaders = new ArrayList<>();
            if (tags.contains("NeoForge")) loaders.add("neoforge");
            if (tags.contains("Forge")) loaders.add("forge");
            if (tags.contains("Fabric")) loaders.add("fabric");
            if (tags.contains("Quilt")) {
                loaders.add("quilt");
                loaders.add("fabric");
            }
            return new Env(mc, loaders);
        } catch (Exception e) {
            return new Env(null, List.of());
        }
    }

    public static List<Entry> list(Path gameDir, Type t) throws IOException {
        Path dir = dir(gameDir, t);
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith(".")) continue;
                boolean isDir = Files.isDirectory(f);

                if (t == Type.WORLDS) {
                    if (!isDir) continue;
                    out.add(world(f, name));
                    continue;
                }

                boolean enabled = !name.endsWith(OFF);
                String base = enabled ? name : name.substring(0, name.length() - OFF.length());
                if (t == Type.MODS) {
                    if (isDir || !base.toLowerCase().endsWith(".jar")) continue;
                } else if (!isDir && !base.toLowerCase().endsWith(".zip")) {
                    continue;
                }
                String display = base;
                String info = "";
                if (t == Type.MODS) {
                    ModInfo mi = ModInfo.read(f);
                    if (mi != null) {
                        if (!mi.name().isBlank()) display = mi.name();
                        info = (mi.version().isBlank() ? "" : mi.version() + "  -  ") + mi.loader() + "  -  " + base;
                    }
                } else if (!isDir) {
                    info = packDescription(f);
                    if (info.isBlank()) info = base;
                } else {
                    info = base;
                }
                long size = 0;
                try {
                    size = isDir ? -1 : Files.size(f);
                } catch (IOException ignored) {
                }
                out.add(new Entry(f, name, display, info, enabled, size, isDir));
            }
        }
        out.sort(Comparator.comparing(e -> e.display().toLowerCase()));
        return out;
    }

    private static Entry world(Path f, String name) {
        String display = name;
        String when = "";
        Path level = f.resolve("level.dat");
        if (Files.exists(level)) {
            String ln = Nbt.levelName(level);
            if (ln != null && !ln.isBlank()) display = ln;
            try {
                when = LocalDateTime.ofInstant(Files.getLastModifiedTime(level).toInstant(), ZoneId.systemDefault()).format(WHEN);
            } catch (IOException ignored) {
            }
        }
        long size = 0;
        try (Stream<Path> w = Files.walk(f)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) size += Files.size(p);
        } catch (IOException ignored) {
        }
        String info = (when.isEmpty() ? "" : when + "  -  ") + name;
        return new Entry(f, name, display, info, true, size, true);
    }

    /** The icon (png bytes) stored inside a mod jar, a pack (pack.png) or a world (icon.png), or null. */
    public static byte[] icon(Entry e, Type t) {
        try {
            if (t == Type.MODS) return e.dir() ? null : ModInfo.icon(e.path());
            Path p = e.path();
            if (e.dir()) {
                Path f = p.resolve(t == Type.WORLDS ? "icon.png" : "pack.png");
                return Files.isRegularFile(f) && Files.size(f) < 3_000_000 ? Files.readAllBytes(f) : null;
            }
            try (ZipFile z = new ZipFile(p.toFile())) {
                ZipEntry en = z.getEntry("pack.png");
                if (en == null || en.getSize() > 3_000_000) return null;
                try (InputStream in = z.getInputStream(en)) {
                    return in.readAllBytes();
                }
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** First line of the description in pack.mcmeta, or "". */
    private static String packDescription(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry("pack.mcmeta");
            if (e == null) return "";
            try (InputStream in = z.getInputStream(e)) {
                String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (s.startsWith("\uFEFF")) s = s.substring(1);
                JsonElement d = JsonParser.parseString(s).getAsJsonObject().getAsJsonObject("pack").get("description");
                String text = d.isJsonPrimitive() ? d.getAsString()
                        : d.isJsonObject() && d.getAsJsonObject().has("text") ? d.getAsJsonObject().get("text").getAsString() : "";
                text = text.replaceAll("\u00A7.", "").replace('\n', ' ').trim();
                return text.length() > 90 ? text.substring(0, 90) + "..." : text;
            }
        } catch (Exception ex) {
            return "";
        }
    }

    public static void setEnabled(Entry e, boolean enable) throws IOException {
        String name = e.fileName();
        String target = enable ? (name.endsWith(OFF) ? name.substring(0, name.length() - OFF.length()) : name)
                : (name.endsWith(OFF) ? name : name + OFF);
        if (target.equals(name)) return;
        Path to = e.path().resolveSibling(target);
        if (Files.exists(to)) throw new IOException("A file named " + target + " already exists.");
        Files.move(e.path(), to);
    }

    public static void delete(Entry e) throws IOException {
        if (e.dir()) {
            try (Stream<Path> s = Files.walk(e.path())) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        } else {
            Files.delete(e.path());
        }
    }

    /** Adds files (or, for worlds, world folders and zips). Returns how many were added. */
    public static int add(Path gameDir, Type t, List<Path> files) throws IOException {
        Path dir = dir(gameDir, t);
        Files.createDirectories(dir);
        int n = 0;
        for (Path f : files) {
            if (t == Type.WORLDS) {
                addWorld(dir, f);
                n++;
                continue;
            }
            if (!Files.isRegularFile(f) || !f.getFileName().toString().toLowerCase().endsWith(t.ext)) continue;
            Files.copy(f, dir.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        return n;
    }

    // ---------- worlds ----------
    private static Path uniqueDir(Path parent, String name) {
        Path p = parent.resolve(name);
        int i = 1;
        while (Files.exists(p)) p = parent.resolve(name + " (" + i++ + ")");
        return p;
    }

    private static void addWorld(Path saves, Path src) throws IOException {
        if (Files.isDirectory(src)) {
            if (!Files.exists(src.resolve("level.dat"))) {
                throw new IOException("This folder is not a Minecraft world (it has no level.dat).");
            }
            Path target = uniqueDir(saves, src.getFileName().toString());
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.toList()) {
                    Path to = target.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else {
                        Files.createDirectories(to.getParent());
                        Files.copy(p, to);
                    }
                }
            }
            return;
        }
        if (!src.getFileName().toString().toLowerCase().endsWith(".zip")) {
            throw new IOException("A world must be a folder or a .zip file.");
        }
        try (ZipFile z = new ZipFile(src.toFile())) {
            String prefix = null;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.equals("level.dat")) {
                    prefix = "";
                    break;
                }
                if (n.endsWith("/level.dat") && n.indexOf('/') == n.length() - "/level.dat".length()) prefix = n.substring(0, n.indexOf('/') + 1);
            }
            if (prefix == null) throw new IOException("No world (level.dat) was found inside this zip.");
            String base = prefix.isEmpty() ? src.getFileName().toString().replaceAll("(?i)\\.zip$", "") : prefix.substring(0, prefix.length() - 1);
            Path target = uniqueDir(saves, base);
            Path root = target.toAbsolutePath().normalize();
            en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(prefix)) continue;
                Path out = root.resolve(n.substring(prefix.length())).normalize();
                if (!out.startsWith(root)) continue; // zip-slip guard
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
