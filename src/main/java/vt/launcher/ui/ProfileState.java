package vt.launcher.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import vt.launcher.core.Config;
import vt.launcher.core.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;

/** Shared profile list + the active profile, used by the sidebar, Home and the Profiles page. */
public final class ProfileState {
    public final Config cfg;
    public final ObservableList<Profile> profiles = FXCollections.observableArrayList();
    public final ObjectProperty<Profile> active = new SimpleObjectProperty<>();
    /** Incremented when a profile was edited (player name, version, skin...), so other views can refresh. */
    public final IntegerProperty updated = new SimpleIntegerProperty();

    public ProfileState(Config cfg) {
        this.cfg = cfg;
        profiles.setAll(cfg.profiles);
        active.set(cfg.active());
        active.addListener((o, a, b) -> {
            if (b != null) {
                cfg.activeProfile = b.id;
                cfg.save();
            }
        });
    }

    public void save() {
        cfg.profiles = new ArrayList<>(profiles);
        cfg.save();
    }

    public void touch() {
        save();
        updated.set(updated.get() + 1);
    }

    public Profile create(String player) {
        Profile base = active.get();
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.player = player;
        p.version = base != null ? base.version : "";
        profiles.add(p);
        save();
        active.set(p);
        return p;
    }

    /** Returns false if it is the last profile (at least one must remain). */
    public boolean delete(Profile p) {
        if (profiles.size() <= 1) return false;
        try {
            Files.deleteIfExists(p.skinFile(cfg));
            Files.deleteIfExists(p.capeFile(cfg));
        } catch (IOException ignored) {
        }
        profiles.remove(p);
        if (active.get() == p || active.get() == null) active.set(profiles.get(0));
        save();
        return true;
    }
}
