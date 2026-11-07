package vt.launcher.ui;

import javafx.scene.image.Image;
import vt.launcher.core.Config;
import vt.launcher.core.DefaultSkins;
import vt.launcher.core.Profile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;

/** The skin picture of a profile: its own skin, or the default Steve / Alex of the chosen model. */
final class Skins {
    private Skins() {}

    static Image imageFor(Config cfg, Profile p) {
        try {
            if (p.hasSkin && Files.exists(p.skinFile(cfg))) {
                Image own = new Image(p.skinFile(cfg).toUri().toString());
                if (!own.isError()) return own;
            }
        } catch (Exception ignored) {
        }
        return defaultImage(cfg, "slim".equals(p.skinModel));
    }

    static Image defaultImage(Config cfg, boolean slim) {
        return new Image(new ByteArrayInputStream(DefaultSkins.png(cfg, slim)));
    }
}
