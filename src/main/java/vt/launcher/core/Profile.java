package vt.launcher.core;

import java.nio.file.Path;

/** A profile is a player: name, skin/cape and the version it last played. All other settings are shared. */
public final class Profile {
    public String id = "";
    public String player = "Player";
    public String version = "";
    public String skinModel = "classic"; // classic | slim
    public boolean hasSkin = false;
    public boolean hasCape = false;

    public Path skinFile(Config cfg) {
        return cfg.vtDir().resolve("skins").resolve(id + ".png");
    }

    public Path capeFile(Config cfg) {
        return cfg.vtDir().resolve("skins").resolve(id + "-cape.png");
    }

    @Override
    public String toString() {
        return player;
    }
}
