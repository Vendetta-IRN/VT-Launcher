package vt.launcher.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import vt.launcher.core.Config;
import vt.launcher.core.LocalImporter;
import vt.launcher.core.LocalImporter.Candidate;
import vt.launcher.core.Loaders;
import vt.launcher.core.Log;
import vt.launcher.core.Manifest;
import vt.launcher.core.Manifest.VersionEntry;
import vt.launcher.core.VersionInstaller;
import vt.launcher.core.VersionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static vt.launcher.ui.I18n.t;

/** Versions: "Installed" (manage, import) and "Online installation" (vanilla or with a mod loader, like HMCL). */
public class VersionsPage extends StackPane {
    private interface Task {
        /** Returns a final status text, or null to use the default one. */
        String run(Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception;
    }

    private final Config cfg;
    private final ObservableList<String> installed;
    private final Runnable onChanged;
    private final VBox main = new VBox(12);

    // online tab
    private final ObservableList<VersionEntry> all = FXCollections.observableArrayList();
    private final FilteredList<VersionEntry> filtered = new FilteredList<>(all, v -> true);
    private final ListView<VersionEntry> list = new ListView<>(filtered);
    private final TextField search = new TextField();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final Button reload = new Button(t("reload"));

    // installed tab
    private final ObservableList<String> localIds = FXCollections.observableArrayList();
    private final Map<String, String> typeOf = new HashMap<>();
    private final ListView<String> localList = new ListView<>(localIds);

    // shared
    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private volatile boolean busy = false;
    private Path lastImportDir;

    public VersionsPage(Config cfg, ObservableList<String> installed, Runnable onChanged) {
        this.cfg = cfg;
        this.installed = installed;
        this.onChanged = onChanged;

        main.getStyleClass().add("page");
        Label title = new Label(t("nav.versions"));
        title.getStyleClass().add("page-title");

        TabPane tabs = new TabPane(localTab(), onlineTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        VBox bottom = new VBox(6, status, bar);
        bottom.setPadding(new Insets(4, 0, 0, 0));
        main.getChildren().addAll(title, tabs, bottom);
        getChildren().add(main);

        installed.addListener((ListChangeListener<String>) c -> {
            list.refresh();
            reloadLocal();
        });
        reloadLocal();
        loadManifest();
    }

    // ================= installed tab =================
    private Tab localTab() {
        Label sub = new Label(t("local.sub"));
        sub.getStyleClass().add("muted");
        sub.setWrapText(true);

        Button imp = new Button(t("import.local"));
        imp.getStyleClass().add("accent-btn");
        imp.setOnAction(e -> importLocal());
        Button openDir = new Button(t("open.versions"));
        openDir.setOnAction(e -> open(cfg.gameDirPath().resolve("versions")));
        HBox tools = new HBox(10, imp, openDir);

        localList.setPlaceholder(new Label(t("no.versions")));
        localList.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(id);
                name.getStyleClass().add("ver-id");
                String type = typeOf.getOrDefault(id, "?");
                Label tag = new Label(type);
                tag.getStyleClass().add(type.equals("Vanilla") ? "tag" : "tag-accent");
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                Button edit = iconButton("M4 20 L8 19 L19 8 L16 5 L5 16 Z M14 7 L17 10", t("edit"));
                edit.setOnAction(e -> openEditor(id));
                Button del = iconButton("M5 7 H19 M9 7 V4 H15 V7 M7 7 L8 20 H16 L17 7", t("delete"));
                del.setDisable(busy);
                del.setOnAction(e -> deleteVersion(id));
                HBox row = new HBox(10, name, tag, sp, edit, del);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        VBox.setVgrow(localList, Priority.ALWAYS);

        VBox box = new VBox(10, sub, tools, localList);
        box.setPadding(new Insets(12, 0, 0, 0));
        return new Tab(t("tab.installed"), box);
    }

    private static Button iconButton(String svg, String tip) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("icon");
        Button b = new Button();
        b.setGraphic(p);
        b.getStyleClass().add("icon-btn");
        return b;
    }

    private void reloadLocal() {
        List<String> ids = new ArrayList<>(installed);
        localIds.setAll(ids);
        Thread.ofVirtual().start(() -> {
            Map<String, String> types = new HashMap<>();
            for (String id : ids) types.put(id, typeLabel(id));
            Platform.runLater(() -> {
                typeOf.clear();
                typeOf.putAll(types);
                localList.refresh();
            });
        });
    }

    private String typeLabel(String id) {
        try {
            Path f = cfg.gameDirPath().resolve("versions").resolve(id).resolve(id + ".json");
            JsonObject v = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return VersionType.label(v);
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- version editor ----
    private void openEditor(String id) {
        VersionEditor ed = new VersionEditor(cfg, id, onChanged, new VersionEditor.Host() {
            @Override
            public void close() {
                closeEditor();
            }

            @Override
            public void installLoader(String mc, Loaders.Kind kind, Loaders.Item item) {
                closeEditor();
                runTask((st, pr) -> Loaders.install(kind, mc, item.value(), cfg, st, pr) + " " + t("done"), null);
            }
        });
        getChildren().setAll(ed);
        Motion.pageIn(ed);
    }

    private void closeEditor() {
        getChildren().setAll(main);
        Motion.pageIn(main);
        reloadLocal();
    }

    private void deleteVersion(String id) {
        List<String> users = new ArrayList<>();
        for (String other : installed) {
            if (other.equals(id)) continue;
            try {
                Path f = cfg.gameDirPath().resolve("versions").resolve(other).resolve(other + ".json");
                JsonObject v = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                if (v.has("inheritsFrom") && v.get("inheritsFrom").getAsString().equals(id)) users.add(other);
            } catch (Exception ignored) {
            }
        }
        String msg = t("delete.confirm") + (users.isEmpty() ? "" : "\n\n" + t("delete.used") + " " + String.join(", ", users));
        Alert a = Dlg.alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setHeaderText(id);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.YES) return;
        try {
            Path dir = cfg.gameDirPath().resolve("versions").resolve(id);
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
            status.setText(id + " " + t("deleted"));
            onChanged.run();
        } catch (IOException e) {
            status.setText(t("failed") + ": " + e.getMessage());
        }
    }

    // ---- import ----
    private void importLocal() {
        if (busy) return;
        Path root = cfg.gameDirPath();
        Optional<Path> picked = FolderPicker.pick(getScene().getWindow(), t("import.pick"), lastImportDir);
        if (picked.isEmpty()) return;
        lastImportDir = picked.get();

        LocalImporter.Scan found;
        try {
            found = LocalImporter.scan(picked.get(), root);
        } catch (IOException e) {
            info(e.getMessage());
            return;
        }
        final LocalImporter.Scan scan = found;
        if (scan.items().isEmpty()) {
            info(t("import.none"));
            return;
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("import.title"));
        dlg.initOwner(getScene().getWindow());
        Dlg.style(dlg);
        ListView<Candidate> lv = new ListView<>(FXCollections.observableArrayList(scan.items()));
        lv.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        lv.getSelectionModel().selectAll();
        lv.setPrefSize(560, 300);
        lv.setCellFactory(l -> new ListCell<Candidate>() {
            @Override
            protected void updateItem(Candidate c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(c.id());
                name.getStyleClass().add("ver-id");
                Label tag = new Label(c.type());
                tag.getStyleClass().add(c.type().equals("Vanilla") ? "tag" : "tag-accent");
                HBox row = new HBox(10, name, tag);
                row.setAlignment(Pos.CENTER_LEFT);
                if (c.installed()) {
                    Label l2 = new Label(t("import.already"));
                    l2.getStyleClass().add("muted");
                    row.getChildren().add(l2);
                }
                setGraphic(row);
                setText(null);
            }
        });
        Label hint = new Label(scan.sourceRoot() != null ? t("import.libs.yes") : t("import.libs.no"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        VBox content = new VBox(10, lv, hint);
        dlg.getDialogPane().setContent(content);
        ButtonType go = new ButtonType(t("import.go"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(go, ButtonType.CANCEL);
        Optional<ButtonType> r = dlg.showAndWait();
        if (r.isEmpty() || r.get() != go) return;

        List<Candidate> chosen = new ArrayList<>(lv.getSelectionModel().getSelectedItems());
        if (chosen.isEmpty()) return;

        runTask((st, pr) -> {
            int imported = 0;
            List<String> errors = new ArrayList<>();
            for (Candidate c : chosen) {
                try {
                    LocalImporter.importVersion(c, scan, root, st, pr);
                    imported++;
                } catch (Exception ex) {
                    Log.error("Import failed: " + c.id(), ex);
                    errors.add(c.id() + ": " + ex.getMessage());
                }
            }
            String sum = imported + " " + t("import.imported");
            if (!errors.isEmpty()) sum += ", " + errors.size() + " " + t("failed") + " - " + errors.get(0);
            return sum;
        }, null);
    }

    // ================= online installation tab =================
    private Tab onlineTab() {
        Label sub = new Label(t("versions.sub"));
        sub.getStyleClass().add("muted");
        sub.setWrapText(true);

        search.setPromptText(t("search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        typeBox.getItems().addAll(t("type.release"), t("type.snapshot"), t("type.old"), t("type.all"));
        typeBox.getSelectionModel().select(0);
        search.textProperty().addListener((o, a, b) -> applyFilter());
        typeBox.valueProperty().addListener((o, a, b) -> applyFilter());
        reload.setOnAction(e -> {
            Manifest.refresh();
            loadManifest();
        });
        HBox tools = new HBox(10, search, typeBox, reload);
        tools.setAlignment(Pos.CENTER_LEFT);
        applyFilter(); // the default "Releases" filter must be active from the start

        list.setPlaceholder(new Label(t("loading")));
        list.setCellFactory(lv -> new ListCell<VersionEntry>() {
            @Override
            protected void updateItem(VersionEntry v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label id = new Label(v.id());
                id.getStyleClass().add("ver-id");
                Label type = new Label(v.type());
                type.getStyleClass().add("tag");
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                HBox row = new HBox(10, id, type, sp);
                row.setAlignment(Pos.CENTER_LEFT);
                if (installed.contains(v.id())) {
                    Label ok = new Label(t("installed"));
                    ok.getStyleClass().add("ok");
                    row.getChildren().add(ok);
                }
                Button b = new Button(t("install"));
                b.getStyleClass().add("accent-btn");
                b.setDisable(busy);
                b.setOnAction(e -> installDialog(v));
                row.getChildren().add(b);
                setGraphic(row);
                setText(null);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = new VBox(10, sub, tools, list);
        box.setPadding(new Insets(12, 0, 0, 0));
        return new Tab(t("tab.online"), box);
    }

    private void installDialog(VersionEntry v) {
        if (busy) return;
        Optional<InstallDialog.Selection> sel = InstallDialog.show(getScene().getWindow(), v.id(), true);
        if (sel.isEmpty()) return;
        InstallDialog.Selection s = sel.get();
        runTask((st, pr) -> {
            if (s.kind() == null) {
                String nid = VersionInstaller.installAsNew(v.id(), v.url(), cfg.gameDirPath(), st, pr);
                return nid + " " + t("done");
            }
            return Loaders.install(s.kind(), v.id(), s.item().value(), cfg, st, pr) + " " + t("done");
        }, null);
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        int type = typeBox.getSelectionModel().getSelectedIndex();
        filtered.setPredicate(v -> v.id().toLowerCase().contains(q) && switch (type) {
            case 0 -> v.type().equals("release");
            case 1 -> v.type().equals("snapshot");
            case 2 -> v.type().startsWith("old");
            default -> true;
        });
    }

    private void loadManifest() {
        status.setText(t("loading"));
        reload.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                var versions = Manifest.load();
                Platform.runLater(() -> {
                    all.setAll(versions);
                    status.setText("");
                    reload.setDisable(false);
                });
            } catch (Exception e) {
                Log.error("Could not load version list", e);
                Platform.runLater(() -> {
                    status.setText(t("load.failed") + ": " + e.getMessage());
                    list.setPlaceholder(new Label(t("load.failed")));
                    reload.setDisable(false);
                });
            }
        });
    }

    // ================= shared task runner =================
    private void runTask(Task task, String doneText) {
        if (busy) return;
        busy = true;
        refreshLists();
        bar.setVisible(true);
        bar.setProgress(-1);
        status.setText(t("starting"));
        Thread.ofPlatform().daemon().start(() -> {
            try {
                String result = task.run(
                        msg -> Platform.runLater(() -> status.setText(msg)),
                        (done, total) -> {
                            if (done.equals(total) || done % 25 == 0) {
                                Platform.runLater(() -> {
                                    bar.setProgress(total == 0 ? -1 : (double) done / total);
                                    if (total > 0) status.setText(t("downloading") + " " + done + " / " + total);
                                });
                            }
                        });
                String text = result != null ? result : doneText;
                Platform.runLater(() -> {
                    status.setText(text == null ? "" : text);
                    finish();
                });
            } catch (Exception e) {
                Log.error("Task failed", e);
                Platform.runLater(() -> {
                    status.setText(t("failed") + ": " + e.getMessage());
                    finish();
                });
            }
        });
    }

    private void finish() {
        bar.setVisible(false);
        busy = false;
        onChanged.run();
        refreshLists();
    }

    private void refreshLists() {
        list.refresh();
        localList.refresh();
    }

    private void info(String msg) {
        Dlg.alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }

    private static void open(Path p) {
        Dlg.browse(p);
    }
}
