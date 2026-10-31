package vt.launcher.ui;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import vt.launcher.core.Profile;

import static vt.launcher.ui.I18n.t;

public class HomePage extends VBox {
    public HomePage(ObservableList<String> installed, ProfileState state) {
        super(14);
        getStyleClass().add("page");

        Label title = new Label("VT Launcher");
        title.getStyleClass().add("page-title");
        Label sub = new Label(t("home.sub"));
        sub.getStyleClass().add("muted");

        Label player = new Label();
        Label version = new Label();
        Label count = new Label();
        Runnable refresh = () -> {
            Profile p = state.active.get();
            player.setText(t("home.player") + ": " + (p == null ? "-" : p.player));
            version.setText(t("home.version") + ": " + (p == null || p.version == null || p.version.isBlank() ? "-" : p.version));
            count.setText(t("home.installed") + ": " + installed.size());
        };
        refresh.run();
        installed.addListener((ListChangeListener<String>) c -> refresh.run());
        state.active.addListener((o, a, b) -> refresh.run());
        state.updated.addListener((o, a, b) -> refresh.run());

        VBox card = new VBox(8, player, version, count);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        getChildren().addAll(title, sub, card);
    }
}
