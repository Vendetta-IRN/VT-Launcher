package vt.launcher.ui;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import vt.launcher.core.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static vt.launcher.ui.I18n.t;

/**
 * The launcher's own file explorer. Three uses:
 * pick a folder, pick files (or folders) with a filter, or just browse a folder.
 * It marks folders it recognises: "Java" (has bin/javaw.exe), "Minecraft" (has a versions folder), "Version".
 */
public final class FolderPicker {
    private enum Mode { FOLDER, BROWSE, ENTRIES }

    private record Place(String name, Path path) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Path lastDir;

    private FolderPicker() {}

    /** Pick a folder. */
    public static Optional<Path> pick(Window owner, String title, Path start) {
        List<Path> r = show(owner, title, start, Mode.FOLDER, null, false, false);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Opens the explorer on a folder (shows files too). Nothing is returned. */
    public static void browse(Window owner, String title, Path start) {
        show(owner, title, start, Mode.BROWSE, null, false, false);
    }

    /** Pick files that pass the filter and, if allowDirs, folders too. */
    public static List<Path> pickEntries(Window owner, String title, Path start, Predicate<Path> fileFilter,
                                         boolean allowDirs, boolean multiple) {
        return show(owner, title, start, Mode.ENTRIES, fileFilter, allowDirs, multiple);
    }

    private static List<Path> show(Window owner, String title, Path start, Mode mode, Predicate<Path> fileFilter,
                                   boolean allowDirs, boolean multiple) {
        Dialog<List<Path>> dlg = new Dialog<>();
        dlg.setTitle(title);
        if (owner != null) dlg.initOwner(owner);
        Dlg.style(dlg);

        View v = new View(mode, fileFilter, allowDirs);

        ListView<Place> places = new ListView<>();
        places.getStyleClass().add("picker-list");
        places.setFixedCellSize(34);
        places.setPrefWidth(170);
        places.getItems().addAll(places());
        places.setOnMouseClicked(e -> {
            Place p = places.getSelectionModel().getSelectedItem();
            if (p != null) v.go(p.path());
        });

        Button up = new Button(t("up"));
        up.setOnAction(e -> {
            Path c = v.current.get();
            if (c != null) v.go(c.getParent());
        });
        v.pathField.setOnAction(e -> {
            try {
                v.go(Path.of(v.pathField.getText().trim()));
            } catch (Exception ignored) {
            }
        });
        HBox.setHgrow(v.pathField, Priority.ALWAYS);
        HBox top = new HBox(8, up, v.pathField);
        top.setAlignment(Pos.CENTER_LEFT);

        v.list.getStyleClass().add("picker-list");
        v.list.setFixedCellSize(34); // same height for every row: no jumping while the mouse moves
        if (mode == Mode.ENTRIES && multiple) v.list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        v.list.setCellFactory(lv -> new ListCell<Path>() {
            private final Label name = new Label();
            private final Label tag = new Label();
            private final Region sp = new Region();
            private final HBox row = new HBox(10, name, sp, tag);

            {
                HBox.setHgrow(sp, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
            }

            @Override
            protected void updateItem(Path p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setGraphic(null);
                    return;
                }
                Path n = p.getFileName();
                name.setText(n == null ? p.toString() : n.toString());
                String b = v.badges.get(p);
                tag.setText(b == null ? "" : b);
                tag.setVisible(b != null);
                tag.setManaged(b != null);
                boolean file = v.files.contains(p);
                tag.getStyleClass().setAll(file ? "tag" : "tag-accent");
                name.setOpacity(file && !v.acceptable(p) ? 0.45 : 1.0);
                setGraphic(row);
            }
        });
        v.list.setOnMouseClicked(e -> {
            Path sel = v.list.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && sel != null && !v.files.contains(sel)) v.go(sel);
        });
        v.list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> v.updateChosen());

        Label chosenTitle = new Label(t("picker.selected") + ":");
        chosenTitle.getStyleClass().add("muted");
        v.chosen.setWrapText(true);
        VBox bottom = new VBox(2, chosenTitle, v.chosen);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setLeft(places);
        pane.setCenter(v.list);
        pane.setBottom(bottom);
        BorderPane.setMargin(places, new Insets(8, 8, 0, 0));
        BorderPane.setMargin(v.list, new Insets(8, 0, 0, 0));
        pane.setPrefSize(740, 460);

        ButtonType select = new ButtonType(t("select"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().setContent(pane);
        if (mode == Mode.BROWSE) {
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.setResultConverter(bt -> null);
        } else {
            dlg.getDialogPane().getButtonTypes().addAll(select, ButtonType.CANCEL);
            v.selectBtn = (Button) dlg.getDialogPane().lookupButton(select);
            dlg.setResultConverter(bt -> bt == select ? v.result() : null);
        }

        Path first = start != null && Files.isDirectory(start) ? start
                : lastDir != null && Files.isDirectory(lastDir) ? lastDir
                : Path.of(System.getProperty("user.home"));
        v.go(first);
        Optional<List<Path>> r = dlg.showAndWait();
        if (mode != Mode.BROWSE && v.current.get() != null) lastDir = v.current.get(); // start here next time
        return r.orElse(List.of());
    }

    private static List<Place> places() {
        List<Place> out = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        add(out, "Home", home);
        add(out, "Desktop", home.resolve("Desktop"));
        add(out, "Downloads", home.resolve("Downloads"));
        add(out, "Documents", home.resolve("Documents"));
        add(out, ".minecraft", Config.defaultGameDir());
        for (File r : File.listRoots()) add(out, r.toString(), r.toPath());
        return out;
    }

    private static void add(List<Place> out, String name, Path p) {
        if (Files.isDirectory(p)) out.add(new Place(name, p));
    }

    private static String sizeText(Path f) {
        try {
            long n = Files.size(f);
            if (n >= 1024 * 1024) return String.format("%.1f MB", n / (1024.0 * 1024.0));
            return Math.max(1, n / 1024) + " KB";
        } catch (IOException e) {
            return null;
        }
    }

    private static String badge(Path p) {
        try {
            if (Files.exists(p.resolve("bin").resolve("javaw.exe"))) return "Java";
            if (Files.isDirectory(p.resolve("versions"))) return "Minecraft";
            if (Files.exists(p.resolve("level.dat"))) return "World";
            Path n = p.getFileName();
            if (n != null && Files.exists(p.resolve(n + ".json"))) return "Version";
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final class View {
        final ObjectProperty<Path> current = new SimpleObjectProperty<>();
        final TextField pathField = new TextField();
        final ListView<Path> list = new ListView<>();
        final Label chosen = new Label();
        final Map<Path, String> badges = new ConcurrentHashMap<>();
        final Set<Path> files = ConcurrentHashMap.newKeySet();
        final Mode mode;
        final Predicate<Path> fileFilter;
        final boolean allowDirs;
        Button selectBtn;

        View(Mode mode, Predicate<Path> fileFilter, boolean allowDirs) {
            this.mode = mode;
            this.fileFilter = fileFilter;
            this.allowDirs = allowDirs;
        }

        boolean showFiles() {
            return mode != Mode.FOLDER;
        }

        /** Can this row be chosen? */
        boolean acceptable(Path p) {
            if (mode == Mode.FOLDER) return !files.contains(p);
            if (mode == Mode.BROWSE) return true;
            if (files.contains(p)) return fileFilter == null || fileFilter.test(p);
            return allowDirs;
        }

        /** p == null shows the list of drives. */
        void go(Path p) {
            if (p != null && !Files.isDirectory(p)) return;
            current.set(p);
            pathField.setText(p == null ? "" : p.toString());
            List<Path> items = new ArrayList<>();
            if (p == null) {
                for (File r : File.listRoots()) items.add(r.toPath());
            } else {
                List<Path> dirs = new ArrayList<>();
                List<Path> regular = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path d : ds) {
                        try {
                            if (Files.isHidden(d)) continue;
                            if (Files.isDirectory(d)) dirs.add(d);
                            else if (showFiles() && Files.isRegularFile(d)) {
                                if (mode == Mode.BROWSE || fileFilter == null || fileFilter.test(d)) regular.add(d);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // no access: show an empty folder
                }
                Comparator<Path> byName = Comparator.comparing(d -> d.getFileName().toString().toLowerCase());
                dirs.sort(byName);
                regular.sort(byName);
                items.addAll(dirs);
                items.addAll(regular);
                files.addAll(regular);
            }
            list.getItems().setAll(items);
            updateChosen();

            // folder badges are looked up in the background, never while drawing a row
            Thread.ofVirtual().start(() -> {
                boolean any = false;
                for (Path d : items) {
                    if (badges.containsKey(d)) continue;
                    String b = files.contains(d) ? sizeText(d) : badge(d);
                    if (b != null) {
                        badges.put(d, b);
                        any = true;
                    }
                }
                if (any) Platform.runLater(list::refresh);
            });
        }

        /** What "Select" returns. */
        List<Path> result() {
            if (mode == Mode.FOLDER) {
                Path s = list.getSelectionModel().getSelectedItem();
                Path r = s != null && !files.contains(s) ? s : current.get();
                return r == null ? List.of() : List.of(r);
            }
            List<Path> out = new ArrayList<>();
            for (Path p : list.getSelectionModel().getSelectedItems()) if (acceptable(p)) out.add(p);
            return out;
        }

        void updateChosen() {
            List<Path> r = result();
            if (mode == Mode.BROWSE) {
                Path s = list.getSelectionModel().getSelectedItem();
                chosen.setText(s == null ? "" : s.toString());
            } else {
                chosen.setText(r.isEmpty() ? "" : r.size() == 1 ? r.get(0).toString() : r.size() + " " + t("content.items"));
            }
            if (selectBtn != null) selectBtn.setDisable(r.isEmpty());
        }
    }
}
