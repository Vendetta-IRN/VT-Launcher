package vt.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** Downloads authlib-injector (the Java agent that lets the game use our local skin server). */
public final class SkinService {
    private static final String LATEST = "https://authlib-injector.yushi.moe/artifact/latest.json";

    private SkinService() {}

    public static Path injectorJar(Config cfg) {
        return cfg.vtDir().resolve("authlib-injector.jar");
    }

    public static Path ensureInjector(Config cfg, Consumer<String> status) throws Exception {
        Path jar = injectorJar(cfg);
        if (Files.exists(jar) && Files.size(jar) > 10_000) return jar;

        // normal case: authlib-injector ships inside the launcher, nothing to download
        try (java.io.InputStream in = SkinService.class.getResourceAsStream("/vt/authlib-injector.jar")) {
            if (in != null) {
                Files.createDirectories(jar.getParent());
                Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
                return jar;
            }
        }

        status.accept("Downloading skin support...");
        byte[] b = Net.getBytes(LATEST);
        JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        String url = o.get("download_url").getAsString();
        String sha256 = o.getAsJsonObject("checksums").get("sha256").getAsString();

        Files.createDirectories(jar.getParent());
        Path tmp = jar.resolveSibling("authlib-injector.jar.part");
        Net.download(url, tmp);
        if (!Downloader.digest(tmp, "SHA-256").equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("authlib-injector checksum mismatch");
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }
}
