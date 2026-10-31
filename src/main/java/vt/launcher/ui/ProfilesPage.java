package vt.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import vt.launcher.core.Config;
import vt.launcher.core.Profile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static vt.launcher.ui.I18n.t;

/** Profiles = players: a player name and a skin / cape. */
public class ProfilesPage extends HBox {
    private final ProfileState st;
    private final Config cfg;
    private final SkinPreview preview = new SkinPreview(250, 330);
    private final TextField playerField = new TextField();
    private final ComboBox<String> model = new ComboBox<>();
    private final ListView<Profile> list;
    private boolean loading = false;

    public ProfilesPage(ProfileState st) {
        super(18);
        this.st = st;
        this.cfg = st.cfg;
        getStyleClass().add("page");

        // ---------- left: profile list ----------
        Label title = new Label(t("nav.profiles"));
        title.getStyleClass().add("page-title");

        list = new ListView<>(st.profiles);
        list.setFixedCellSize(54);
        list.setCellFactory(lv -> new ListCell<Profile>() {
            @Override
            protected void updateItem(Profile p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label n = new Label(p.player);
                n.getStyleClass().add("ver-id");
                Label sub = new Label(p.version == null || p.version.isBlank() ? "-" : p.version);
                sub.getStyleClass().add("muted");
                setGraphic(new VBox(2, n, sub));
                setText(null);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        list.getSelectionModel().select(st.active.get());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b != st.active.get()) st.active.set(b);
        });

        Button add = new Button(t("profile.new"));
        add.getStyleClass().add("accent-btn");
        add.setOnAction(e -> newProfile());
        Button del = new Button(t("delete"));
        del.setOnAction(e -> deleteProfile());
        HBox btns = new HBox(8, add, del);

        VBox left = new VBox(12, title, list, btns);
        left.setPrefWidth(250);
        left.setMinWidth(250);

        // ---------- right: player + skin ----------
        playerField.textProperty().addListener((o, old, now) -> {
            if (!now.matches("[A-Za-z0-9_]{0,16}")) playerField.setText(old);
        });
        playerField.setOnAction(e -> savePlayer());
        HBox.setHgrow(playerField, Priority.ALWAYS);
        Button savePlayer = new Button(t("save"));
        savePlayer.getStyleClass().add("accent-btn");
        savePlayer.setOnAction(e -> savePlayer());
        HBox playerRow = new HBox(8, playerField, savePlayer);
        Label playerLabel = new Label(t("profile.player"));
        playerLabel.getStyleClass().add("muted");
        VBox info = new VBox(4, playerLabel, playerRow);

        Button chooseSkin = new Button(t("skin.choose"));
        chooseSkin.setOnAction(e -> chooseSkin());
        Button removeSkin = new Button(t("skin.remove"));
        removeSkin.setOnAction(e -> removeSkin());
        model.getItems().addAll(t("skin.classic"), t("skin.slim"));
        model.setOnAction(e -> {
            if (loading) return;
            Profile p = st.active.get();
            if (p == null) return;
            p.skinModel = model.getSelectionModel().getSelectedIndex() == 1 ? "slim" : "classic";
            st.touch();
            refreshPreview();
        });
        Button chooseCape = new Button(t("cape.choose"));
        chooseCape.setOnAction(e -> chooseCape());
        Button removeCape = new Button(t("cape.remove"));
        removeCape.setOnAction(e -> removeCape());

        Label hint = new Label(t("skin.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(260);

        VBox skinControls = new VBox(10,
                new Label(t("skin.model")), model,
                chooseSkin, removeSkin,
                chooseCape, removeCape,
                hint);
        HBox skinRow = new HBox(18, preview, skinControls);

        Label skinTitle = new Label(t("skin.title"));
        skinTitle.getStyleClass().add("section-title");
        VBox card = new VBox(16, info, skinTitle, skinRow);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        ScrollPane sp = new ScrollPane(card);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("settings-scroll");
        HBox.setHgrow(sp, Priority.ALWAYS);

        getChildren().addAll(left, sp);

        st.active.addListener((o, a, b) -> {
            if (b != null && list.getSelectionModel().getSelectedItem() != b) list.getSelectionModel().select(b);
            load(b);
        });
        st.updated.addListener((o, a, b) -> list.refresh());
        load(st.active.get());
    }

    private void load(Profile p) {
        if (p == null) return;
        loading = true;
        playerField.setText(p.player);
        model.getSelectionModel().select("slim".equals(p.skinModel) ? 1 : 0);
        loading = false;
        refreshPreview();
    }

    private void refreshPreview() {
        Profile p = st.active.get();
        if (p == null) return;
        Image skin = null;
        Image cape = null;
        if (p.hasSkin && Files.exists(p.skinFile(cfg))) skin = new Image(p.skinFile(cfg).toUri().toString());
        if (p.hasCape && Files.exists(p.capeFile(cfg))) cape = new Image(p.capeFile(cfg).toUri().toString());
        preview.setSkin(skin, "slim".equals(p.skinModel), cape);
    }

    private void savePlayer() {
        Profile p = st.active.get();
        if (p == null || loading) return;
        String n = playerField.getText().trim();
        if (n.length() < 3) {
            info(t("profile.short"));
            playerField.setText(p.player);
            return;
        }
        if (!n.equals(p.player)) {
            p.player = n;
            st.touch();
        }
    }

    // ---------- profile actions ----------
    private void newProfile() {
        TextInputDialog d = new TextInputDialog("");
        d.setTitle(t("profile.new"));
        d.setHeaderText(null);
        d.setContentText(t("profile.player"));
        d.initOwner(getScene().getWindow());
        Dlg.style(d);
        // same rules as the player name field: letters, digits and _ only, 3 to 16 characters
        TextField editor = d.getEditor();
        editor.textProperty().addListener((o, old, now) -> {
            if (!now.matches("[A-Za-z0-9_]{0,16}")) editor.setText(old);
        });
        d.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(editor.textProperty().length().lessThan(3));
        d.showAndWait().map(String::trim).filter(s -> s.matches("[A-Za-z0-9_]{3,16}")).ifPresent(st::create);
    }

    private void deleteProfile() {
        Profile p = st.active.get();
        if (p == null) return;
        if (st.profiles.size() <= 1) {
            info(t("profile.last"));
            return;
        }
        Alert a = Dlg.alert(Alert.AlertType.CONFIRMATION, t("profile.delete.confirm"), ButtonType.YES, ButtonType.NO);
        a.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> st.delete(p));
    }

    // ---------- skin / cape ----------
    private File pickPng(String title) {
        List<Path> r = FolderPicker.pickEntries(getScene().getWindow(), title, null,
                f -> f.getFileName().toString().toLowerCase().endsWith(".png"), false, false);
        return r.isEmpty() ? null : r.get(0).toFile();
    }

    private void chooseSkin() {
        Profile p = st.active.get();
        if (p == null) return;
        File f = pickPng(t("skin.choose"));
        if (f == null) return;
        try {
            BufferedImage bi = ImageIO.read(f);
            if (bi == null || bi.getWidth() != 64 || (bi.getHeight() != 64 && bi.getHeight() != 32)) {
                info(t("skin.invalid"));
                return;
            }
            Path dest = p.skinFile(cfg);
            Files.createDirectories(dest.getParent());
            Files.copy(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            p.hasSkin = true;
            st.touch();
            refreshPreview();
        } catch (IOException e) {
            info(e.getMessage());
        }
    }

    private void removeSkin() {
        Profile p = st.active.get();
        if (p == null) return;
        try {
            Files.deleteIfExists(p.skinFile(cfg));
        } catch (IOException ignored) {
        }
        p.hasSkin = false;
        st.touch();
        refreshPreview();
    }

    private void chooseCape() {
        Profile p = st.active.get();
        if (p == null) return;
        File f = pickPng(t("cape.choose"));
        if (f == null) return;
        try {
            BufferedImage bi = ImageIO.read(f);
            if (bi == null || bi.getWidth() != 64 || bi.getHeight() != 32) {
                info(t("cape.invalid"));
                return;
            }
            Path dest = p.capeFile(cfg);
            Files.createDirectories(dest.getParent());
            Files.copy(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            p.hasCape = true;
            st.touch();
            refreshPreview();
        } catch (IOException e) {
            info(e.getMessage());
        }
    }

    private void removeCape() {
        Profile p = st.active.get();
        if (p == null) return;
        try {
            Files.deleteIfExists(p.capeFile(cfg));
        } catch (IOException ignored) {
        }
        p.hasCape = false;
        st.touch();
        refreshPreview();
    }

    private void info(String msg) {
        Dlg.alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }
}
