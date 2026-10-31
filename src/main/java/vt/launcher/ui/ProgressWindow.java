package vt.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Small modal window with a status line and a progress bar. Methods can be called from any thread. */
public final class ProgressWindow {
    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar(-1);

    public ProgressWindow(Window owner, String title) {
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(false);

        Label t = new Label(title);
        t.getStyleClass().add("dlg-title");
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(14, t, status, bar);
        box.getStyleClass().add("vt-dialog");
        box.setPadding(new Insets(20));
        Scene sc = new Scene(box, 460, 150);
        sc.setFill(Color.TRANSPARENT);
        if (Dlg.css() != null) sc.getStylesheets().add(Dlg.css());
        stage.setScene(sc);
        stage.setOnCloseRequest(e -> e.consume()); // cannot be closed while working
    }

    public void show() {
        stage.show();
    }

    public void status(String s) {
        Platform.runLater(() -> status.setText(s));
    }

    public void progress(Integer done, Integer total) {
        Platform.runLater(() -> bar.setProgress(total == null || total == 0 ? -1 : (double) done / total));
    }

    public void close() {
        Platform.runLater(stage::close);
    }
}
