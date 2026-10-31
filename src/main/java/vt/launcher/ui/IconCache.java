package vt.launcher.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import vt.launcher.core.ContentManager;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Loads icons from inside mod jars and packs in the background and remembers them (40x40, small in memory). */
final class IconCache {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NONE = ConcurrentHashMap.newKeySet();

    private IconCache() {}

    private static String key(ContentManager.Entry e) {
        long m = 0;
        try {
            m = Files.getLastModifiedTime(e.path()).toMillis();
        } catch (Exception ignored) {
        }
        String name = e.fileName().endsWith(".disabled") ? e.fileName().substring(0, e.fileName().length() - 9) : e.fileName();
        return e.path().getParent() + "|" + name + "|" + m;
    }

    /** onLoaded runs on the JavaFX thread. Nothing happens if the file has no icon. */
    static void load(ContentManager.Entry e, ContentManager.Type type, Consumer<Image> onLoaded) {
        String key = key(e);
        Image cached = CACHE.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        if (NONE.contains(key)) return;
        Thread.ofVirtual().start(() -> {
            Image img = null;
            byte[] b = ContentManager.icon(e, type);
            if (b != null) {
                Image i = new Image(new ByteArrayInputStream(b), 40, 40, true, true);
                if (!i.isError()) img = i;
            }
            if (img == null) {
                NONE.add(key);
                return;
            }
            CACHE.put(key, img);
            Image fin = img;
            Platform.runLater(() -> onLoaded.accept(fin));
        });
    }
}
