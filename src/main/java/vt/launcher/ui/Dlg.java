package vt.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gives every dialog the launcher's own look: no Windows title bar, rounded corners, our close button. */
public final class Dlg {
    private static Window main;
    private static String css;

    private Dlg() {}

    public static void init(Window mainWindow, String stylesheet) {
        main = mainWindow;
        css = stylesheet;
    }

    /** Opens a folder in the launcher's own file explorer (creates it first if needed). */
    public static void browse(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
        }
        Path n = folder.getFileName();
        FolderPicker.browse(main, n == null ? folder.toString() : n.toString(), folder);
    }

    static String css() {
        return css;
    }

    public static Alert alert(Alert.AlertType type, String content, ButtonType... buttons) {
        Alert a = new Alert(type, content, buttons);
        a.setTitle("VT Launcher");
        style(a);
        return a;
    }

    public static void style(Dialog<?> d) {
        d.initStyle(StageStyle.TRANSPARENT);
        if (main != null && d.getOwner() == null) d.initOwner(main);

        DialogPane pane = d.getDialogPane();
        if (css != null && !pane.getStylesheets().contains(css)) pane.getStylesheets().add(css);
        pane.getStyleClass().add("vt-dialog");
        pane.setNodeOrientation(I18n.rtl() ? javafx.geometry.NodeOrientation.RIGHT_TO_LEFT : javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        pane.setGraphic(null);
        pane.getScene().setFill(Color.TRANSPARENT);

        Label title = new Label();
        title.textProperty().bind(d.titleProperty());
        title.getStyleClass().add("dlg-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Chrome.winButton("M5 5 L19 19 M19 5 L5 19");
        close.getStyleClass().add("win-close");
        close.setOnAction(e -> d.close());
        HBox bar = new HBox(8, title, sp, close);
        bar.getStyleClass().add("dlg-bar");
        bar.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);

        Label sub = new Label();
        sub.textProperty().bind(pane.headerTextProperty());
        sub.getStyleClass().add("dlg-sub");
        sub.setWrapText(true);
        sub.visibleProperty().bind(pane.headerTextProperty().isNotEmpty());
        sub.managedProperty().bind(sub.visibleProperty());

        VBox header = new VBox(8, bar, sub);
        header.setPadding(new Insets(0, 0, 4, 0));
        pane.setHeader(header);

        // drag the dialog by its bar
        double[] off = new double[2];
        bar.setOnMousePressed(e -> {
            Window w = pane.getScene().getWindow();
            off[0] = e.getScreenX() - w.getX();
            off[1] = e.getScreenY() - w.getY();
        });
        bar.setOnMouseDragged(e -> {
            Window w = pane.getScene().getWindow();
            w.setX(e.getScreenX() - off[0]);
            w.setY(e.getScreenY() - off[1]);
        });
    }
}
