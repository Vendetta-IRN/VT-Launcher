package vt.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import vt.launcher.core.Loaders;
import vt.launcher.core.Loaders.Kind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static vt.launcher.ui.I18n.t;

/** "Install version X": vanilla only, or with one mod loader. The loader versions for that Minecraft version are listed. */
final class InstallDialog {
    /** kind == null means "vanilla only". */
    record Selection(Kind kind, Loaders.Item item) {}

    private InstallDialog() {}

    static Optional<Selection> show(Window owner, String mc, boolean allowVanilla) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("install.title") + " " + mc);
        if (owner != null) dlg.initOwner(owner);
        Dlg.style(dlg);

        ToggleGroup group = new ToggleGroup();
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new Insets(6, 4, 6, 4));
        int row = 0;

        if (allowVanilla) {
            RadioButton vanilla = new RadioButton("Minecraft " + mc + "  (" + t("content.vanilla") + ")");
            vanilla.setToggleGroup(group);
            vanilla.setUserData(null);
            vanilla.setSelected(true);
            grid.add(vanilla, 0, row++, 2, 1);
        }

        Map<Kind, ComboBox<Loaders.Item>> combos = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            RadioButton rb = new RadioButton(k.display());
            rb.setToggleGroup(group);
            rb.setUserData(k);
            rb.setDisable(true);
            ComboBox<Loaders.Item> cb = new ComboBox<>();
            cb.setPrefWidth(300);
            cb.setDisable(true);
            Label state = new Label(t("loading"));
            state.getStyleClass().add("muted");
            rb.selectedProperty().addListener((o, a, b) -> cb.setDisable(!b));
            combos.put(k, cb);

            grid.add(rb, 0, row);
            grid.add(cb, 1, row);
            grid.add(state, 2, row);
            row++;

            Thread.ofVirtual().start(() -> {
                try {
                    List<Loaders.Item> items = Loaders.versions(k, mc);
                    Platform.runLater(() -> {
                        if (items.isEmpty()) {
                            state.setText(t("install.na"));
                            return;
                        }
                        cb.getItems().setAll(items);
                        cb.setValue(items.get(0));
                        rb.setDisable(false);
                        state.setText(items.size() + "");
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> state.setText(t("failed")));
                }
            });
        }

        Label note = new Label(t("install.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        note.setMaxWidth(560);
        grid.add(note, 0, row, 3, 1);

        ButtonType install = new ButtonType(t("install"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(install, ButtonType.CANCEL);
        Button ok = (Button) dlg.getDialogPane().lookupButton(install);
        ok.disableProperty().bind(group.selectedToggleProperty().isNull());

        Optional<ButtonType> r = dlg.showAndWait();
        if (r.isEmpty() || r.get() != install) return Optional.empty();
        Toggle sel = group.getSelectedToggle();
        if (sel == null) return Optional.empty();
        Kind kind = (Kind) sel.getUserData();
        if (kind == null) return Optional.of(new Selection(null, null));
        Loaders.Item item = combos.get(kind).getValue();
        return item == null ? Optional.empty() : Optional.of(new Selection(kind, item));
    }
}
