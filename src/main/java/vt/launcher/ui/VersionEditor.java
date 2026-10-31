package vt.launcher.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import vt.launcher.core.Config;
import vt.launcher.core.ContentManager;
import vt.launcher.core.ContentManager.Type;
import vt.launcher.core.Loaders;
import vt.launcher.core.VersionManager;
import vt.launcher.core.VersionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static vt.launcher.ui.I18n.t;

/** Manage one installed version: rename, own game folder, mods, resource packs, shaders and loaders. */
final class VersionEditor extends VBox {
    interface Host {
        void close();

        void installLoader(String mc, Loaders.Kind kind, Loaders.Item item);
    }

    private final Config cfg;
    private final Runnable onChanged;
    private final Host host;
    private String id;
    private final Label title = new Label();
    private final Label typeLabel = new Label();
    private final TextField nameField = new TextField();
    private final Label hint = new Label();
    private final Label loaderInfo = new Label();
    private final List<ContentPanel> panels = new ArrayList<>();

    VersionEditor(Config cfg, String id, Runnable onChanged, Host host) {
        super(12);
        this.cfg = cfg;
        this.id = id;
        this.onChanged = onChanged;
        this.host = host;
        getStyleClass().add("page");

        Button back = new Button(t("back"));
        back.setOnAction(e -> host.close());
        title.getStyleClass().add("page-title");
        HBox head = new HBox(14, back, title);
        head.setAlignment(Pos.CENTER_LEFT);

        // ---- info / rename / folder ----
        typeLabel.getStyleClass().add("muted");
        nameField.setText(id);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        Button rename = new Button(t("rename"));
        rename.getStyleClass().add("accent-btn");
        rename.setOnAction(e -> rename());
        nameField.setOnAction(e -> rename());
        HBox nameRow = new HBox(8, new Label(t("edit.name")), nameField, rename);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox isolated = new CheckBox(t("edit.isolated"));
        isolated.setSelected(cfg.isolated(id));
        isolated.setOnAction(e -> {
            cfg.setIsolated(this.id, isolated.isSelected());
            updateHint();
            panels.forEach(ContentPanel::reload);
        });
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        Button openDir = new Button(t("folder"));
        openDir.setOnAction(e -> open(cfg.gameDirPath().resolve("versions").resolve(this.id)));
        HBox actions = new HBox(8, openDir);

        VBox card = new VBox(10, typeLabel, nameRow, isolated, hint, actions);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        // ---- content tabs ----
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (Type type : Type.values()) {
            ContentPanel p = new ContentPanel(cfg, type, () -> cfg.gameDirFor(this.id), () -> ContentManager.env(cfg, this.id));
            panels.add(p);
            String name = switch (type) {
                case MODS -> t("content.mods");
                case RESOURCEPACKS -> t("content.resourcepacks");
                case SHADERS -> t("content.shaders");
                case WORLDS -> t("content.worlds");
            };
            tabs.getTabs().add(new Tab(name, p));
        }
        tabs.getTabs().add(new Tab(t("edit.loaders"), loadersTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        getChildren().addAll(head, card, tabs);
        refreshInfo();
        panels.forEach(ContentPanel::reload);
    }

    private VBox loadersTab() {
        loaderInfo.setWrapText(true);
        Button add = new Button(t("edit.addloader"));
        add.getStyleClass().add("accent-btn");
        add.setOnAction(e -> {
            String mc = ContentManager.env(cfg, id).mc();
            if (mc == null) {
                Dlg.alert(Alert.AlertType.INFORMATION, t("edit.nomc")).showAndWait();
                return;
            }
            Optional<InstallDialog.Selection> sel = InstallDialog.show(getScene().getWindow(), mc, false);
            if (sel.isPresent() && sel.get().kind() != null) host.installLoader(mc, sel.get().kind(), sel.get().item());
        });
        Label note = new Label(t("edit.loaders.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        VBox box = new VBox(12, loaderInfo, add, note);
        box.setPadding(new Insets(14, 0, 0, 0));
        return box;
    }

    private void refreshInfo() {
        title.setText(id);
        nameField.setText(id);
        String type = "?";
        String mc = "?";
        try {
            Path f = cfg.gameDirPath().resolve("versions").resolve(id).resolve(id + ".json");
            JsonObject v = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            type = VersionType.label(v);
            String m = ContentManager.env(cfg, id).mc();
            if (m != null) mc = m;
        } catch (Exception ignored) {
        }
        typeLabel.setText(t("edit.type") + ": " + type + "      Minecraft: " + mc);
        loaderInfo.setText(t("edit.loader.current") + ": " + type);
        updateHint();
    }

    private void updateHint() {
        hint.setText(cfg.isolated(id) ? t("edit.isolated.on") : t("edit.isolated.off"));
    }

    private void rename() {
        String n = nameField.getText().trim();
        if (n.equals(id)) return;
        try {
            VersionManager.rename(cfg, id, n);
            id = n;
            onChanged.run();
            refreshInfo();
            panels.forEach(ContentPanel::reload);
        } catch (IOException ex) {
            Dlg.alert(Alert.AlertType.WARNING, ex.getMessage()).showAndWait();
            nameField.setText(id);
        }
    }

    private static void open(Path p) {
        Dlg.browse(p);
    }
}
