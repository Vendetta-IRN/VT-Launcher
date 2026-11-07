package vt.launcher.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import vt.launcher.core.Log;
import vt.launcher.core.WorldExport;
import vt.launcher.core.WorldExport.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static vt.launcher.ui.I18n.t;

/** Asks for a folder, then exports the given worlds into it as zip files (with a progress window). */
final class WorldExporter {
    private WorldExporter() {}

    static void run(Window owner, List<World> worlds) {
        if (worlds.isEmpty()) return;
        Optional<Path> dest = FolderPicker.pick(owner, t("world.export.where"), null);
        if (dest.isEmpty()) return;
        Path folder = dest.get();

        ProgressWindow pw = new ProgressWindow(owner, t("world.exporting"));
        pw.show();
        Thread.ofPlatform().daemon().start(() -> {
            List<Path> made = new ArrayList<>();
            String error = null;
            try {
                int i = 0;
                for (World w : worlds) {
                    pw.status(w.name() + "   (" + (++i) + " / " + worlds.size() + ")");
                    made.add(WorldExport.export(w.path(), w.name(), folder, pw::progress));
                }
            } catch (Exception ex) {
                Log.error("World export failed", ex);
                error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            String err = error;
            Platform.runLater(() -> {
                pw.close();
                if (err != null) {
                    Alert a = Dlg.alert(Alert.AlertType.ERROR, err);
                    a.setHeaderText(t("failed"));
                    a.showAndWait();
                    return;
                }
                ButtonType open = new ButtonType(t("folder"));
                String names = made.size() == 1 ? made.get(0).getFileName().toString() : made.size() + " zip";
                Alert ok = Dlg.alert(Alert.AlertType.INFORMATION, t("world.exported") + "\n" + names + "\n\n" + folder, open, ButtonType.OK);
                ok.showAndWait().ifPresent(b -> {
                    if (b == open) Dlg.browse(folder);
                });
            });
        });
    }
}
