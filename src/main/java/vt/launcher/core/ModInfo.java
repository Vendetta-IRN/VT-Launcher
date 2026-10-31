package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads a mod's name, version and loader from its jar (fabric.mod.json, quilt.mod.json, mods.toml, mcmod.info). */
public record ModInfo(String name, String version, String loader) {
    private static final Pattern NAME = Pattern.compile("^\\s*displayName\\s*=\\s*\"(.*?)\"", Pattern.MULTILINE);
    private static final Pattern LOGO = Pattern.compile("^\\s*logoFile\\s*=\\s*\"(.*?)\"", Pattern.MULTILINE);
    private static final Pattern VERSION = Pattern.compile("^\\s*version\\s*=\\s*\"(.*?)\"", Pattern.MULTILINE);

    /** Returns null when the jar has no known metadata. */
    public static ModInfo read(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                JsonObject o = json(z, e).getAsJsonObject();
                return new ModInfo(str(o, "name", str(o, "id", "")), str(o, "version", ""), "Fabric");
            }
            e = z.getEntry("quilt.mod.json");
            if (e != null) {
                JsonObject q = json(z, e).getAsJsonObject().getAsJsonObject("quilt_loader");
                JsonObject meta = q.has("metadata") ? q.getAsJsonObject("metadata") : new JsonObject();
                return new ModInfo(str(meta, "name", str(q, "id", "")), str(q, "version", ""), "Quilt");
            }
            e = z.getEntry("META-INF/neoforge.mods.toml");
            if (e != null) return toml(text(z, e), "NeoForge");
            e = z.getEntry("META-INF/mods.toml");
            if (e != null) return toml(text(z, e), "Forge");
            e = z.getEntry("mcmod.info");
            if (e != null) {
                JsonElement root = json(z, e);
                JsonObject m = null;
                if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) m = root.getAsJsonArray().get(0).getAsJsonObject();
                else if (root.isJsonObject() && root.getAsJsonObject().has("modList")) {
                    m = root.getAsJsonObject().getAsJsonArray("modList").get(0).getAsJsonObject();
                }
                if (m != null) return new ModInfo(str(m, "name", str(m, "modid", "")), str(m, "version", ""), "Forge");
            }
        } catch (Exception ignored) {
            // unreadable jar: shown by file name
        }
        return null;
    }

    /** The mod's icon (png) from inside the jar, or null. Looks where each loader says, then at common names. */
    public static byte[] icon(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            String name = null;
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                name = iconName(json(z, e).getAsJsonObject().get("icon"));
            } else {
                e = z.getEntry("quilt.mod.json");
                if (e != null) {
                    JsonObject q = json(z, e).getAsJsonObject().getAsJsonObject("quilt_loader");
                    if (q != null && q.has("metadata")) name = iconName(q.getAsJsonObject("metadata").get("icon"));
                } else {
                    e = z.getEntry("META-INF/neoforge.mods.toml");
                    if (e == null) e = z.getEntry("META-INF/mods.toml");
                    if (e != null) {
                        String t = text(z, e);
                        int at = t.indexOf("[[mods]]");
                        Matcher m = LOGO.matcher(at >= 0 ? t.substring(at) : t);
                        if (m.find()) name = m.group(1);
                    } else {
                        e = z.getEntry("mcmod.info");
                        if (e != null) {
                            JsonElement root = json(z, e);
                            JsonObject m = null;
                            if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) m = root.getAsJsonArray().get(0).getAsJsonObject();
                            else if (root.isJsonObject() && root.getAsJsonObject().has("modList")) {
                                m = root.getAsJsonObject().getAsJsonArray("modList").get(0).getAsJsonObject();
                            }
                            if (m != null) name = str(m, "logoFile", null);
                        }
                    }
                }
            }
            byte[] b = name == null ? null : readEntry(z, name);
            if (b == null) {
                for (String n : new String[]{"icon.png", "logo.png", "pack.png"}) {
                    b = readEntry(z, n);
                    if (b != null) break;
                }
            }
            return b;
        } catch (Exception ex) {
            return null;
        }
    }

    /** "icon" is a path, or an object like {"128": "path"}: take the biggest size. */
    private static String iconName(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            String best = null;
            int bestSize = -1;
            for (var en : el.getAsJsonObject().entrySet()) {
                int size;
                try {
                    size = Integer.parseInt(en.getKey());
                } catch (NumberFormatException nf) {
                    size = 0;
                }
                if (size > bestSize && en.getValue().isJsonPrimitive()) {
                    bestSize = size;
                    best = en.getValue().getAsString();
                }
            }
            return best;
        }
        return null;
    }

    private static byte[] readEntry(ZipFile z, String name) throws Exception {
        String n = name.startsWith("/") ? name.substring(1) : name;
        ZipEntry e = z.getEntry(n);
        if (e == null || e.isDirectory() || e.getSize() > 3_000_000) return null;
        try (InputStream in = z.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static ModInfo toml(String s, String loader) {
        int at = s.indexOf("[[mods]]");
        String part = at >= 0 ? s.substring(at) : s;
        Matcher n = NAME.matcher(part);
        Matcher v = VERSION.matcher(part);
        String name = n.find() ? n.group(1) : "";
        String ver = v.find() ? v.group(1) : "";
        if (ver.contains("${")) ver = ""; // not filled in by the build
        return new ModInfo(name, ver, loader);
    }

    private static String text(ZipFile z, ZipEntry e) throws Exception {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonElement json(ZipFile z, ZipEntry e) throws Exception {
        String s = text(z, e);
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return JsonParser.parseString(s);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }
}
