package vt.launcher.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import vt.launcher.core.CrashAnalyzer.Report;

import java.nio.file.Path;

import static vt.launcher.ui.I18n.t;

/** The window shown when the game cannot start or crashes: what happened, what to do, and the log lines. */
final class ErrorDialog {
    private ErrorDialog() {}

    static void show(Report r) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("error.title"));
        dlg.setResizable(true);
        Dlg.style(dlg);

        Label head = new Label(r.diagnosis().title());
        head.getStyleClass().add("error-title");
        head.setWrapText(true);

        Label whyH = new Label(t("error.what"));
        whyH.getStyleClass().add("section-title");
        Label why = new Label(r.diagnosis().why());
        why.setWrapText(true);

        Label fixH = new Label(t("error.fix"));
        fixH.getStyleClass().add("section-title");
        Label fix = new Label(r.diagnosis().fix());
        fix.setWrapText(true);

        Label detH = new Label(t("error.details"));
        detH.getStyleClass().add("muted");
        TextArea details = new TextArea(r.details());
        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefRowCount(11);
        details.getStyleClass().add("mono");

        VBox box = new VBox(8, head, whyH, why, fixH, fix, detH, details);
        box.setPrefWidth(660);
        box.setPadding(new Insets(4, 4, 0, 4));

        ButtonType copy = new ButtonType(t("error.copy"), ButtonBar.ButtonData.LEFT);
        ButtonType log = new ButtonType(t("error.log"), ButtonBar.ButtonData.LEFT);
        ButtonType close = new ButtonType(t("error.close"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(copy, log, close);

        // Copy must not close the window
        Button copyBtn = (Button) dlg.getDialogPane().lookupButton(copy);
        copyBtn.addEventFilter(ActionEvent.ACTION, e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(r.diagnosis().title() + "\n" + r.diagnosis().why() + "\n\n" + r.details());
            Clipboard.getSystemClipboard().setContent(c);
            copyBtn.setText(t("error.copied"));
            e.consume();
        });
        Button logBtn = (Button) dlg.getDialogPane().lookupButton(log);
        logBtn.addEventFilter(ActionEvent.ACTION, e -> {
            Path folder = r.crashReport() != null ? r.crashReport().getParent()
                    : r.log() != null ? r.log().getParent() : null;
            if (folder != null) Dlg.browse(folder);
            e.consume();
        });
        dlg.showAndWait();
    }
}
