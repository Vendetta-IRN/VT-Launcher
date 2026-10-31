package vt.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Config {
    public String username = "Player";      // old single-account value, moved into the first profile
    public String selectedVersion = "";     // old value, moved into the first profile
    public String gameDir = "";
    public boolean useMirror = false;

    public static final String DEFAULT_JVM_ARGS = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200";
    public int ramMb = 2048;
    public int resW = 0;
    public int resH = 0;
    public boolean fullscreen = false;
    public String jvmArgs = DEFAULT_JVM_ARGS;
    public String afterLaunch = "keep"; // keep | minimize | close
    public boolean autoBackup = false;
    public String language = "en"; // en | fa
    public List<String> customJavas = new ArrayList<>();

    public List<Profile> profiles = new ArrayList<>();
    public String activeProfile = "";

    /** Per-version options. isolated = the version has its own game folder (saves, mods...). */
    public static final class VersionOpts {
        public Boolean isolated;
    }

    public Map<String, VersionOpts> versionOpts = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---------- folders ----------
    public static Path defaultGameDir() {
        String appdata = System.getenv("APPDATA");
        Path base = appdata != null ? Path.of(appdata) : Path.of(System.getProperty("user.home"));
        return base.resolve(".minecraft");
    }

    // The config file always lives in the default .minecraft, even if the game folder is changed.
    public static Path file() {
        return defaultGameDir().resolve("vt-launcher").resolve("config.json");
    }

    public Path gameDirPath() {
        return gameDir == null || gameDir.isBlank() ? defaultGameDir() : Path.of(gameDir);
    }

    public Path vtDir() {
        return gameDirPath().resolve("vt-launcher");
    }

    // ---------- per-version game folder ----------
    /** Versions with a mod loader get their own folder by default; plain vanilla shares .minecraft. */
    public boolean isolated(String id) {
        VersionOpts o = versionOpts == null ? null : versionOpts.get(id);
        if (o != null && o.isolated != null) return o.isolated;
        return hasLoader(id);
    }

    public void setIsolated(String id, boolean on) {
        if (versionOpts == null) versionOpts = new HashMap<>();
        VersionOpts o = versionOpts.computeIfAbsent(id, k -> new VersionOpts());
        o.isolated = on;
        save();
    }

    /** The folder the game runs in (saves, mods, resourcepacks, shaderpacks, options). */
    public Path gameDirFor(String id) {
        return isolated(id) ? gameDirPath().resolve("versions").resolve(id) : gameDirPath();
    }

    private boolean hasLoader(String id) {
        try {
            Path f = gameDirPath().resolve("versions").resolve(id).resolve(id + ".json");
            JsonObject v = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> tags = VersionType.detect(v);
            return !(tags.size() == 1 && tags.get(0).equals("Vanilla"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- load / save ----------
    public static Config load() {
        Config c = null;
        try {
            Path f = file();
            if (Files.exists(f)) c = GSON.fromJson(Files.readString(f), Config.class);
        } catch (Exception ignored) {
        }
        if (c == null) c = new Config();
        c.fix();
        c.ensureProfiles();
        return c;
    }

    public static Config fromFile(Path f) throws IOException {
        Config c;
        try {
            c = GSON.fromJson(Files.readString(f), Config.class);
        } catch (Exception e) {
            throw new IOException("This is not a launcher settings file.");
        }
        if (c == null) throw new IOException("This is not a launcher settings file.");
        c.fix();
        return c;
    }

    private void fix() {
        if (profiles == null) profiles = new ArrayList<>();
        if (customJavas == null) customJavas = new ArrayList<>();
        if (versionOpts == null) versionOpts = new HashMap<>();
        if (jvmArgs == null) jvmArgs = DEFAULT_JVM_ARGS;
        if (afterLaunch == null) afterLaunch = "keep";
        if (language == null || language.isBlank()) language = "en";
        if (ramMb < 512) ramMb = 2048;
    }

    /** Copies everything except the game folder from another settings object. */
    public void importFrom(Config o) {
        ramMb = o.ramMb;
        resW = o.resW;
        resH = o.resH;
        fullscreen = o.fullscreen;
        jvmArgs = o.jvmArgs;
        afterLaunch = o.afterLaunch;
        autoBackup = o.autoBackup;
        language = o.language;
        useMirror = o.useMirror;
        customJavas = new ArrayList<>(o.customJavas);
        versionOpts = new HashMap<>(o.versionOpts);
        if (!o.profiles.isEmpty()) {
            profiles = new ArrayList<>(o.profiles);
            activeProfile = o.activeProfile;
        }
        fix();
        ensureProfiles();
    }

    /** Makes sure there is at least one profile (migrates the old single username/version). */
    public void ensureProfiles() {
        if (profiles == null) profiles = new ArrayList<>();
        if (profiles.isEmpty()) {
            Profile p = new Profile();
            p.id = "default";
            p.player = username == null || username.isBlank() ? "Player" : username;
            p.version = selectedVersion == null ? "" : selectedVersion;
            profiles.add(p);
            activeProfile = p.id;
            save();
        }
        if (active() == null) activeProfile = profiles.get(0).id;
    }

    public Profile active() {
        for (Profile p : profiles) if (p.id.equals(activeProfile)) return p;
        return null;
    }

    public void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(this));
        } catch (IOException e) {
            Log.error("Could not save config", e);
        }
    }
}
