package vt.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static vt.launcher.ui.I18n.t;

/** Full-window loading screen shown from the moment PLAY is pressed until the game window opens. */
final class LaunchOverlay extends StackPane {
    private final Label title = new Label();
    private final Label sub = new Label();
    private final Label percent = new Label("0%");
    private final ProgressBar bar = new ProgressBar(-1);
    private final Label stage = new Label();
    private final Label detail = new Label();
    private final Button cancel = new Button(t("launch.cancel"));
    private double shown = 0;
    private Runnable onCancel = () -> {};
    private FadeTransition fade;

    LaunchOverlay() {
        getStyleClass().add("launch-scrim");
        title.getStyleClass().add("page-title");
        sub.getStyleClass().add("muted");
        percent.getStyleClass().add("launch-percent");
        bar.setPrefWidth(440);
        bar.setMaxWidth(440);
        bar.setPrefHeight(10);
        stage.getStyleClass().add("ver-id");
        detail.getStyleClass().add("muted");
        detail.getStyleClass().add("mono");
        detail.setMaxWidth(440);
        detail.setWrapText(false);
        detail.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        cancel.setOnAction(e -> onCancel.run());

        VBox card = new VBox(10, title, sub, percent, bar, stage, detail, cancel);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("launch-card");
        card.setPadding(new Insets(34, 44, 30, 44));
        card.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        VBox.setMargin(cancel, new Insets(10, 0, 0, 0));
        getChildren().add(card);
        setAlignment(Pos.CENTER);
        setVisible(false);
        setOpacity(0);
    }

    void open(String version, String player, Runnable cancelAction) {
        onCancel = cancelAction;
        title.setText(t("launch.title"));
        sub.setText(version + "   -   " + player);
        shown = 0;
        percent.setText("0%");
        bar.setProgress(-1);
        stage.setText(t("launching"));
        detail.setText("");
        cancel.setDisable(false);
        fadeTo(1, true);
    }

    /** fraction < 0 keeps the bar where it is (only the text changes). The bar never moves backwards. */
    void update(String stageText, String detailText, double fraction) {
        if (stageText != null && !stageText.isBlank()) stage.setText(stageText);
        detail.setText(detailText == null ? "" : detailText);
        if (fraction >= 0) {
            shown = Math.max(shown, Math.min(1, fraction));
            bar.setProgress(shown);
            percent.setText((int) Math.round(shown * 100) + "%");
        }
    }

    /** Game window is open: show 100 % for a moment, then fade out. */
    void finish() {
        shown = 1;
        bar.setProgress(1);
        percent.setText("100%");
        stage.setText(t("launch.ready"));
        detail.setText("");
        cancel.setDisable(true);
        FadeTransition f = new FadeTransition(Duration.millis(450), this);
        f.setDelay(Duration.millis(350));
        f.setToValue(0);
        f.setOnFinished(e -> setVisible(false));
        replace(f);
        f.play();
    }

    void close() {
        fadeTo(0, false);
    }

    private void fadeTo(double to, boolean show) {
        if (show) setVisible(true);
        FadeTransition f = new FadeTransition(Duration.millis(180), this);
        f.setToValue(to);
        if (!show) f.setOnFinished(e -> setVisible(false));
        replace(f);
        f.play();
    }

    private void replace(FadeTransition f) {
        if (fade != null) fade.stop();
        fade = f;
    }
}
