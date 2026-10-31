package vt.launcher.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vt.launcher.core.Config;
import vt.launcher.core.ContentManager;
import vt.launcher.core.ContentManager.Entry;
import vt.launcher.core.ContentManager.Type;
import vt.launcher.core.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static vt.launcher.ui.I18n.t;

/** Mods, resource packs or shader packs of one game folder: local list (with icons). */
public class ContentPanel extends VBox {
    private final Config cfg;
    private final Type type;
    private final Supplier<Path> gameDir;
    private final Supplier<ContentManager.Env> env;
    private final ObservableList<Entry> all = FXCollections.observableArrayList();
    private final FilteredList<Entry> shown = new FilteredList<>(all, e -> true);
    private final ListView<Entry> list = new ListView<>(shown);
    private final TextField filter = new TextField();
    private final Label count = new Label();

    public ContentPanel(Config cfg, Type type, Supplier<Path> gameDir, Supplier<ContentManager.Env> env) {
        super(10);
        this.cfg = cfg;
        this.type = type;
        this.gameDir = gameDir;
        this.env = env;
        setPadding(new Insets(12, 0, 0, 0));

        filter.setPromptText(t("search"));
        HBox.setHgrow(filter, Priority.ALWAYS);
        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase();
            shown.setPredicate(e -> e.display().toLowerCase().contains(q) || e.fileName().toLowerCase().contains(q));
        });

        Button add = new Button(t("content.add"));
        add.setOnAction(e -> chooseFiles());
        add.getStyleClass().add("accent-btn");
        Button open = new Button(t("folder"));
        open.setOnAction(e -> Dlg.browse(ContentManager.dir(gameDir.get(), type)));
        Button refresh = new Button(t("reload"));
        refresh.setOnAction(e -> reload());
        HBox tools = new HBox(8, filter, add, open, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);

        count.getStyleClass().add("muted");

        list.setPlaceholder(new Label(t("loading")));
        list.setCellFactory(lv -> new ListCell<Entry>() {
            @Override
            protected void updateItem(Entry en, boolean empty) {
                super.updateItem(en, empty);
                if (empty || en == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                CheckBox on = new CheckBox();
                on.setSelected(en.enabled());
                on.setOnAction(ev -> toggle(en, on.isSelected()));
                on.setVisible(type.toggles());
                on.setManaged(type.toggles());

                // icon from inside the file; a letter shows until (or if never) one is found
                String d = en.display();
                Label fallback = new Label(d.isEmpty() ? "?" : d.substring(0, 1).toUpperCase());
                fallback.getStyleClass().add("icon-fallback");
                ImageView iv = new ImageView();
                iv.setFitWidth(40);
                iv.setFitHeight(40);
                iv.setPreserveRatio(true);
                StackPane iconBox = new StackPane(fallback, iv);
                iconBox.setMinSize(40, 40);
                iconBox.setMaxSize(40, 40);
                if (!en.enabled()) iconBox.setOpacity(0.4);
                IconCache.load(en, type, img -> {
                    iv.setImage(img);
                    fallback.setVisible(false);
                });

                Label name = new Label(en.display());
                name.getStyleClass().add("ver-id");
                if (!en.enabled()) name.setOpacity(0.5);
                Label info = new Label(en.info());
                info.getStyleClass().add("muted");
                VBox text = new VBox(2, name, info);
                HBox.setHgrow(text, Priority.ALWAYS);

                Label size = new Label(en.size() < 0 ? t("folder") : fmtSize(en.size()));
                size.getStyleClass().add("muted");
                Button del = new Button(t("delete"));
                del.setOnAction(ev -> delete(en));

                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                HBox row = new HBox(12, on, iconBox, text, sp, size, del);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        // drag & drop files onto the list
        list.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        list.setOnDragDropped(ev -> {
            if (ev.getDragboard().hasFiles()) {
                addFiles(ev.getDragboard().getFiles().stream().map(File::toPath).toList());
                ev.setDropCompleted(true);
            }
            ev.consume();
        });

        Label hint = new Label(type == Type.WORLDS ? t("content.hint.worlds") : t("content.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        getChildren().addAll(tools, count, list, hint);
    }

    public void reload() {
        Path dir = gameDir.get();
        list.setPlaceholder(new Label(t("loading")));
        Thread.ofVirtual().start(() -> {
            try {
                List<Entry> es = ContentManager.list(dir, type);
                Platform.runLater(() -> {
                    all.setAll(es);
                    long off = es.stream().filter(e -> !e.enabled()).count();
                    count.setText(es.size() + " " + t("content.items") + (off > 0 ? ",  " + off + " " + t("content.disabled") : ""));
                    list.setPlaceholder(new Label(t("content.empty")));
                });
            } catch (Exception e) {
                Log.error("Could not list " + type, e);
                Platform.runLater(() -> list.setPlaceholder(new Label(t("failed") + ": " + e.getMessage())));
            }
        });
    }

    private void toggle(Entry en, boolean on) {
        try {
            ContentManager.setEnabled(en, on);
        } catch (IOException e) {
            info(e.getMessage());
        }
        reload();
    }

    private void delete(Entry en) {
        Alert a = Dlg.alert(Alert.AlertType.CONFIRMATION, en.display(), ButtonType.YES, ButtonType.NO);
        a.setHeaderText(t("content.delete.confirm"));
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.YES) return;
        try {
            ContentManager.delete(en);
        } catch (IOException e) {
            info(e.getMessage());
        }
        reload();
    }

    private void chooseFiles() {
        boolean worlds = type == Type.WORLDS;
        List<Path> files = FolderPicker.pickEntries(getScene().getWindow(), t(worlds ? "content.add.world" : "content.add"), null,
                f -> f.getFileName().toString().toLowerCase().endsWith(type.ext), worlds, true);
        if (!files.isEmpty()) addFiles(files);
    }

    private void addFiles(List<Path> files) {
        try {
            int n = ContentManager.add(gameDir.get(), type, files);
            if (n == 0) info(t("content.wrongtype") + " " + type.ext);
        } catch (IOException e) {
            info(e.getMessage());
        }
        reload();
    }

    private static String fmtSize(long b) {
        if (b >= 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024.0));
        return Math.max(1, b / 1024) + " KB";
    }

    private void info(String msg) {
        Dlg.alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }
}
