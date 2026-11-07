package vt.launcher.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The default Steve (classic) and Alex (slim) skins, shown in the launcher and sent to the game when a profile
 * has no skin of its own.
 *
 * Where the picture comes from, in this order:
 *  1. vt/skins/steve.png and vt/skins/alex.png inside the launcher (put your own files there to override),
 *  2. a copy taken from a Minecraft client jar that is installed here (cached in vt-launcher/skins),
 *  3. a simple drawn Steve / Alex, so there is always something to show.
 */
public final class DefaultSkins {
    private static final String[] STEVE_ENTRIES = {
            "assets/minecraft/textures/entity/player/wide/steve.png",
            "assets/minecraft/textures/entity/steve.png",
            "assets/minecraft/textures/entity/player/steve.png"};
    private static final String[] ALEX_ENTRIES = {
            "assets/minecraft/textures/entity/player/slim/alex.png",
            "assets/minecraft/textures/entity/alex.png",
            "assets/minecraft/textures/entity/player/alex.png"};

    private static byte[] steve;
    private static byte[] alex;

    private DefaultSkins() {}

    /** PNG bytes of the default skin for a model: slim = Alex, otherwise Steve. Never null. */
    public static synchronized byte[] png(Config cfg, boolean slim) {
        byte[] cached = slim ? alex : steve;
        if (cached != null) return cached;

        byte[] b = fromResource(slim ? "/vt/skins/alex.png" : "/vt/skins/steve.png");
        if (b == null) b = fromCache(cfg, slim);
        if (b == null) b = fromClientJar(cfg, slim);
        if (b == null) b = draw(slim);

        if (slim) alex = b;
        else steve = b;
        return b;
    }

    public static boolean isDefault(Profile p, Config cfg) {
        return !(p.hasSkin && Files.exists(p.skinFile(cfg)));
    }

    // ---------- sources ----------
    private static byte[] fromResource(String path) {
        try (InputStream in = DefaultSkins.class.getResourceAsStream(path)) {
            return in == null ? null : valid(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }

    private static Path cacheFile(Config cfg, boolean slim) {
        return cfg.vtDir().resolve("skins").resolve(slim ? "default-alex.png" : "default-steve.png");
    }

    private static byte[] fromCache(Config cfg, boolean slim) {
        try {
            Path f = cacheFile(cfg, slim);
            return Files.isRegularFile(f) ? valid(Files.readAllBytes(f)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] fromClientJar(Config cfg, boolean slim) {
        Path versions = cfg.gameDirPath().resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        List<Path> dirs;
        try (Stream<Path> s = Files.list(versions)) {
            dirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return null;
        }
        for (Path d : dirs) {
            Path jar = d.resolve(d.getFileName() + ".jar");
            if (!Files.isRegularFile(jar)) continue;
            try (ZipFile z = new ZipFile(jar.toFile())) {
                for (String name : slim ? ALEX_ENTRIES : STEVE_ENTRIES) {
                    ZipEntry e = z.getEntry(name);
                    if (e == null) continue;
                    try (InputStream in = z.getInputStream(e)) {
                        byte[] b = valid(in.readAllBytes());
                        if (b == null) continue;
                        try {
                            Path out = cacheFile(cfg, slim);
                            Files.createDirectories(out.getParent());
                            Files.write(out, b);
                        } catch (IOException ignored) {
                        }
                        return b;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Only 64x64 or 64x32 pngs are accepted. */
    private static byte[] valid(byte[] b) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            if (img != null && img.getWidth() == 64 && (img.getHeight() == 64 || img.getHeight() == 32)) return b;
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------- drawn fallback ----------
    private static byte[] draw(boolean slim) {
        int skin = 0xFFC98F6B;
        int hair = slim ? 0xFFD9923A : 0xFF4A3322;
        int shirt = slim ? 0xFF6DBA3E : 0xFF00A8A8;
        int pants = slim ? 0xFF5A4A8C : 0xFF3A3AA0;
        int shoes = 0xFF6B6B6B;
        int arm = slim ? 3 : 4;

        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // head
        box(img, 0, 0, 8, 8, 8, skin);
        rect(img, 8, 0, 8, 8, hair);        // top
        rect(img, 8, 8, 8, 2, hair);        // front: fringe
        rect(img, 0, 8, 8, 3, hair);        // sides and back
        rect(img, 16, 8, 8, 3, hair);
        rect(img, 24, 8, 8, 8, hair);
        px(img, 10, 12, 0xFFFFFFFF); px(img, 11, 12, 0xFF3A2A8A);
        px(img, 14, 12, 0xFF3A2A8A); px(img, 15, 12, 0xFFFFFFFF);
        rect(img, 11, 14, 2, 1, 0xFF8A4F3A);
        // body, legs, arms
        box(img, 16, 16, 8, 12, 4, shirt);
        box(img, 0, 16, 4, 12, 4, pants);
        box(img, 16, 48, 4, 12, 4, pants);
        rect(img, 0, 27, 16, 1, shoes);
        rect(img, 16, 59, 16, 1, shoes);
        box(img, 40, 16, arm, 12, 4, shirt);
        box(img, 32, 48, arm, 12, 4, shirt);
        rect(img, 40, 20 + 10, arm * 2 + 8, 2, skin);
        rect(img, 32, 48 + 14, arm * 2 + 8, 2, skin);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fills the six faces of a box that uses Minecraft's skin layout, starting at u,v. */
    private static void box(BufferedImage img, int u, int v, int w, int h, int d, int color) {
        rect(img, u + d, v, w * 2, d, color);        // top + bottom
        rect(img, u, v + d, d * 2 + w * 2, h, color); // right, front, left, back
    }

    private static void rect(BufferedImage img, int x, int y, int w, int h, int color) {
        for (int j = y; j < y + h && j < 64; j++) {
            for (int i = x; i < x + w && i < 64; i++) img.setRGB(i, j, color);
        }
    }

    private static void px(BufferedImage img, int x, int y, int color) {
        img.setRGB(x, y, color);
    }
}
