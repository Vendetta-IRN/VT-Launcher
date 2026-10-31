package vt.launcher.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import vt.launcher.core.BackupService;
import vt.launcher.core.Config;
import vt.launcher.core.FolderMover;
import vt.launcher.core.JavaRuntime;
import vt.launcher.core.Log;
import vt.launcher.core.Net;
import vt.launcher.core.SystemInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static vt.launcher.ui.I18n.t;

public class SettingsPage extends ScrollPane {
    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss");

    private final Config cfg;
    private final Runnable onGameDirChanged;

    public SettingsPage(Config cfg, Runnable onGameDirChanged) {
        this.cfg = cfg;
        this.onGameDirChanged = onGameDirChanged;
        Label title = new Label(t("nav.settings"));
        title.getStyleClass().add("page-title");

        VBox content = new VBox(16, title,
                general(),
                performance(),
                display(),
                javaSection(),
                behavior(),
                backups(),
                settingsFile());
        content.getStyleClass().add("page");
        content.setPadding(new Insets(28, 30, 20, 30));

        setContent(content);
        setFitToWidth(true);
        getStyleClass().add("settings-scroll");
    }

    private VBox section(String titleKey, Node... nodes) {
        Label l = new Label(t(titleKey));
        l.getStyleClass().add("section-title");
        VBox box = new VBox(12, l);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));
        return box;
    }

    // ---------- General ----------
    private VBox general() {
        ComboBox<String> lang = new ComboBox<>();
        lang.getItems().addAll("English", "\u0641\u0627\u0631\u0633\u06CC");
        lang.getSelectionModel().select("fa".equals(cfg.language) ? 1 : 0);
        lang.setOnAction(e -> {
            String l = lang.getSelectionModel().getSelectedIndex() == 1 ? "fa" : "en";
            if (!l.equals(cfg.language)) {
                cfg.language = l;
                cfg.save();
                Platform.runLater(vt.launcher.App::rebuildUi); // the whole interface is rebuilt in the new language
            }
        });

        TextField dir = new TextField(cfg.gameDirPath().toString());
        dir.setEditable(false);
        HBox.setHgrow(dir, Priority.ALWAYS);
        Button browse = new Button(t("browse"));
        browse.setOnAction(e -> changeGameDir(dir));
        Button reset = new Button(t("reset"));
        reset.setOnAction(e -> applyDir(Config.defaultGameDir(), dir));
        HBox dirRow = new HBox(8, dir, browse, reset);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox mirror = new CheckBox(t("mirror"));
        mirror.setSelected(cfg.useMirror);
        mirror.setOnAction(e -> {
            cfg.useMirror = mirror.isSelected();
            Net.mirror = cfg.useMirror;
            cfg.save();
        });

        Label cacheMsg = new Label();
        cacheMsg.getStyleClass().add("muted");
        Button clear = new Button(t("clear.cache"));
        clear.setOnAction(e -> cacheMsg.setText(clearPartFiles(cfg.gameDirPath())));

        Button openGame = new Button(t("open.folder"));
        openGame.setOnAction(e -> open(cfg.gameDirPath()));
        Button openLogs = new Button(t("open.logs"));
        openLogs.setOnAction(e -> open(cfg.vtDir().resolve("logs")));

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.addRow(0, new Label(t("language")), lang);
        grid.addRow(1, new Label(t("game.folder")), dirRow);
        grid.addRow(2, new Label(t("network")), mirror);
        grid.addRow(3, new Label(t("cache")), new HBox(10, clear, cacheMsg));
        grid.addRow(4, new Label(t("folders")), new HBox(8, openGame, openLogs));
        GridPane.setHgrow(dirRow, Priority.ALWAYS);
        return section("section.general", grid);
    }

    // ---------- Performance ----------
    private static final int RAM_MIN = 512;
    private static final int RAM_STEP = 128;

    private VBox performance() {
        long totalMb = SystemInfo.totalMb();
        int maxMb = (int) Math.max(2048, Math.min(65536, (totalMb * 3 / 4)));
        int steps = (maxMb - RAM_MIN) / RAM_STEP;

        // The slider counts steps (0, 1, 2 ...), so one move of the thumb is always exactly one step of 128 MB.
        Slider ram = new Slider(0, steps, stepOf(cfg.ramMb, steps));
        ram.setMajorTickUnit(1);
        ram.setMinorTickCount(0);
        ram.setSnapToTicks(true);
        ram.setBlockIncrement(1);
        HBox.setHgrow(ram, Priority.ALWAYS);

        Label value = new Label();
        value.setMinWidth(170);
        value.getStyleClass().add("ver-id");
        value.setText(ramText(stepOf(cfg.ramMb, steps)));

        ram.valueProperty().addListener((o, a, b) -> {
            double d = b.doubleValue();
            int i = (int) Math.round(d);
            if (Math.abs(d - i) > 1e-9) {
                ram.setValue(i); // never stay between two steps
                return;
            }
            value.setText(ramText(i));
            if (!ram.isValueChanging()) {
                cfg.ramMb = RAM_MIN + i * RAM_STEP;
                cfg.save();
            }
        });
        ram.valueChangingProperty().addListener((o, was, is) -> {
            if (!is) {
                cfg.ramMb = RAM_MIN + (int) Math.round(ram.getValue()) * RAM_STEP;
                cfg.save();
            }
        });
        ram.setOnScroll(e -> { // mouse wheel: one step per notch
            if (e.getDeltaY() == 0) return;
            ram.setValue(Math.max(0, Math.min(steps, Math.round(ram.getValue()) + (e.getDeltaY() > 0 ? 1 : -1))));
            e.consume();
        });
        HBox ramRow = new HBox(14, ram, value);
        ramRow.setAlignment(Pos.CENTER_LEFT);

        Label ramHint = new Label(t("ram.hint") + " " + (RAM_MIN + steps * RAM_STEP) + " MB");
        ramHint.getStyleClass().add("muted");

        TextField jvm = new TextField(cfg.jvmArgs == null ? "" : cfg.jvmArgs);
        HBox.setHgrow(jvm, Priority.ALWAYS);
        jvm.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                cfg.jvmArgs = jvm.getText().trim();
                cfg.save();
            }
        });
        Button jvmReset = new Button(t("reset"));
        jvmReset.setOnAction(e -> {
            jvm.setText(Config.DEFAULT_JVM_ARGS);
            cfg.jvmArgs = Config.DEFAULT_JVM_ARGS;
            cfg.save();
        });
        HBox jvmRow = new HBox(8, jvm, jvmReset);
        jvmRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(t("jvm.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.addRow(0, new Label(t("ram")), ramRow);
        grid.add(ramHint, 1, 1);
        grid.addRow(2, new Label(t("jvm.args")), jvmRow);
        GridPane.setHgrow(ramRow, Priority.ALWAYS);
        GridPane.setHgrow(jvmRow, Priority.ALWAYS);
        return section("section.performance", grid, hint);
    }

    // ---------- Display ----------
    private VBox display() {
        TextField w = numberField(cfg.resW);
        TextField h = numberField(cfg.resH);
        Runnable save = () -> {
            cfg.resW = parse(w.getText());
            cfg.resH = parse(h.getText());
            cfg.save();
        };
        w.focusedProperty().addListener((o, was, is) -> {
            if (!is) save.run();
        });
        h.focusedProperty().addListener((o, was, is) -> {
            if (!is) save.run();
        });
        HBox res = new HBox(8, w, new Label("x"), h);
        res.setAlignment(Pos.CENTER_LEFT);

        CheckBox full = new CheckBox(t("fullscreen"));
        full.setSelected(cfg.fullscreen);
        full.setOnAction(e -> {
            cfg.fullscreen = full.isSelected();
            cfg.save();
        });

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.addRow(0, new Label(t("resolution")), res);
        grid.addRow(1, new Label(""), full);
        return section("section.display", grid);
    }

    private static int stepOf(int mb, int steps) {
        return Math.max(0, Math.min(steps, Math.round((mb - RAM_MIN) / (float) RAM_STEP)));
    }

    private static String ramText(int step) {
        int mb = RAM_MIN + step * RAM_STEP;
        return mb + " MB  (" + String.format("%.2f", mb / 1024.0) + " GB)";
    }

    private static TextField numberField(int v) {
        TextField f = new TextField(v > 0 ? String.valueOf(v) : "");
        f.setPromptText("auto");
        f.setPrefWidth(90);
        f.textProperty().addListener((o, old, now) -> {
            if (!now.matches("\\d{0,5}")) f.setText(old);
        });
        return f;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Java ----------
    private VBox javaSection() {
        ObservableList<String> javas = FXCollections.observableArrayList(cfg.customJavas);
        ListView<String> lv = new ListView<>(javas);
        lv.setPrefHeight(110);
        lv.setPlaceholder(new Label(t("java.none")));
        lv.setCellFactory(l -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item + "   (Java " + JavaRuntime.majorOf(Path.of(item)) + ")");
            }
        });

        Button add = new Button(t("java.add"));
        add.setOnAction(e -> {
            Optional<Path> sel = FolderPicker.pick(getScene().getWindow(), t("java.add"), null);
            if (sel.isEmpty()) return;
            Path p = sel.get();
            if (JavaRuntime.exeOf(p) == null || JavaRuntime.majorOf(p) < 0) {
                Dlg.alert(Alert.AlertType.WARNING, t("java.invalid")).showAndWait();
                return;
            }
            if (!javas.contains(p.toString())) javas.add(p.toString());
            cfg.customJavas = new ArrayList<>(javas);
            cfg.save();
        });
        Button remove = new Button(t("java.remove"));
        remove.setOnAction(e -> {
            String sel = lv.getSelectionModel().getSelectedItem();
            if (sel != null) {
                javas.remove(sel);
                cfg.customJavas = new ArrayList<>(javas);
                cfg.save();
            }
        });

        Label hint = new Label(t("java.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        Label where = new Label(t("java.where") + ": " + JavaRuntime.baseDir(cfg));
        where.getStyleClass().add("muted");
        where.setWrapText(true);

        return section("section.java", hint, lv, new HBox(8, add, remove), where);
    }

    // ---------- After launch (options shown right here) ----------
    private VBox behavior() {
        ToggleGroup g = new ToggleGroup();
        RadioButton keep = radio(t("after.keep"), "keep", g);
        RadioButton min = radio(t("after.minimize"), "minimize", g);
        RadioButton close = radio(t("after.close"), "close", g);
        String cur = cfg.afterLaunch == null ? "keep" : cfg.afterLaunch;
        (cur.equals("close") ? close : cur.equals("minimize") ? min : keep).setSelected(true);
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) {
                cfg.afterLaunch = (String) b.getUserData();
                cfg.save();
            }
        });
        Label note = new Label(t("after.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        Label label = new Label(t("after.launch"));
        label.getStyleClass().add("muted");
        VBox opts = new VBox(10, label, keep, min, close, note);
        return section("section.behavior", opts);
    }

    private static RadioButton radio(String text, String value, ToggleGroup g) {
        RadioButton r = new RadioButton(text);
        r.setUserData(value);
        r.setToggleGroup(g);
        return r;
    }

    // ---------- Backups (all worlds) ----------
    private VBox backups() {
        CheckBox auto = new CheckBox(t("backup.auto"));
        auto.setSelected(cfg.autoBackup);
        auto.setOnAction(e -> {
            cfg.autoBackup = auto.isSelected();
            cfg.save();
        });
        Label hint = new Label(t("backup.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        ListView<Path> list = new ListView<>();
        list.setPrefHeight(150);
        list.setPlaceholder(new Label(t("backup.none")));
        list.setCellFactory(lv -> new ListCell<Path>() {
            @Override
            protected void updateItem(Path p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    return;
                }
                String name = p.getFileName().toString().replace(".zip", "");
                String when = name;
                try {
                    when = LocalDateTime.parse(name, IN).format(OUT);
                } catch (Exception ignored) {
                }
                String size = "";
                try {
                    size = "     " + Files.size(p) / (1024 * 1024) + " MB";
                } catch (IOException ignored) {
                }
                setText(when + size);
            }
        });
        Runnable reload = () -> list.getItems().setAll(BackupService.list(cfg));
        reload.run();

        Label status = new Label();
        status.getStyleClass().add("muted");
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        boolean[] busy = {false};

        interface Work {
            String run() throws Exception;
        }
        java.util.function.Consumer<Work> run = w -> {
            if (busy[0]) return;
            busy[0] = true;
            bar.setVisible(true);
            status.setText("...");
            Thread.ofPlatform().daemon().start(() -> {
                String msg;
                try {
                    msg = w.run();
                } catch (Exception ex) {
                    Log.error("Backup task failed", ex);
                    msg = t("failed") + ": " + ex.getMessage();
                }
                String text = msg;
                Platform.runLater(() -> {
                    status.setText(text);
                    bar.setVisible(false);
                    busy[0] = false;
                    reload.run();
                });
            });
        };

        Button now = new Button(t("backup.now"));
        now.getStyleClass().add("accent-btn");
        now.setOnAction(e -> run.accept(() -> t("backup.done") + ": "
                + BackupService.create(cfg, (a, b) -> {
        }).getFileName()));
        Button restore = new Button(t("backup.restore"));
        restore.setOnAction(e -> {
            Path sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = Dlg.alert(Alert.AlertType.CONFIRMATION, t("backup.confirm"), ButtonType.YES, ButtonType.NO);
            Optional<ButtonType> r = a.showAndWait();
            if (r.isEmpty() || r.get() != ButtonType.YES) return;
            run.accept(() -> {
                BackupService.restore(cfg, sel);
                return t("backup.restored");
            });
        });
        Button del = new Button(t("delete"));
        del.setOnAction(e -> {
            Path sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try {
                Files.deleteIfExists(sel);
            } catch (IOException ignored) {
            }
            reload.run();
        });
        Button openB = new Button(t("folder"));
        openB.setOnAction(e -> open(BackupService.dir(cfg)));
        HBox btns = new HBox(8, now, restore, del, openB);

        return section("backup", auto, hint, list, btns, status, bar);
    }

    // ---------- Settings file ----------
    private VBox settingsFile() {
        Label hint = new Label(t("settings.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        Label status = new Label();
        status.getStyleClass().add("muted");

        Button exp = new Button(t("settings.export"));
        exp.setOnAction(e -> {
            Optional<Path> dir = FolderPicker.pick(getScene().getWindow(), t("settings.export.where"), null);
            if (dir.isEmpty()) return;
            try {
                cfg.save();
                Path target = dir.get().resolve("vt-launcher-settings.json");
                Files.copy(Config.file(), target, StandardCopyOption.REPLACE_EXISTING);
                status.setText(t("settings.exported") + ": " + target);
            } catch (IOException ex) {
                status.setText(t("failed") + ": " + ex.getMessage());
            }
        });
        Button imp = new Button(t("settings.import"));
        imp.getStyleClass().add("accent-btn");
        imp.setOnAction(e -> {
            List<Path> picked = FolderPicker.pickEntries(getScene().getWindow(), t("settings.import"), null,
                    f -> f.getFileName().toString().toLowerCase().endsWith(".json"), false, false);
            if (picked.isEmpty()) return;
            try {
                Config other = Config.fromFile(picked.get(0));
                cfg.importFrom(other);
                cfg.save();
                Net.mirror = cfg.useMirror;
                Dlg.alert(Alert.AlertType.INFORMATION, t("settings.imported")).showAndWait();
                Platform.runLater(vt.launcher.App::rebuildUi);
            } catch (IOException ex) {
                status.setText(t("failed") + ": " + ex.getMessage());
            }
        });
        return section("section.settingsfile", hint, new HBox(8, imp, exp), status);
    }

    // ---------- game folder ----------
    private void changeGameDir(TextField dirField) {
        Path old = cfg.gameDirPath();
        FolderPicker.pick(getScene().getWindow(), t("game.folder"), old).ifPresent(nd -> {
            if (nd.toAbsolutePath().normalize().equals(old.toAbsolutePath().normalize())) return;
            ButtonType move = new ButtonType(t("folder.move"));
            ButtonType keep = new ButtonType(t("folder.keep"));
            Alert a = Dlg.alert(Alert.AlertType.CONFIRMATION,
                    t("folder.ask") + "\n\n" + old + "\n-> " + nd, move, keep, ButtonType.CANCEL);
            a.showAndWait().ifPresent(b -> {
                if (b == keep) applyDir(nd, dirField);
                else if (b == move) moveDir(old, nd, dirField);
            });
        });
    }

    private void applyDir(Path path, TextField dirField) {
        boolean isDefault = path.toAbsolutePath().normalize().equals(Config.defaultGameDir().toAbsolutePath().normalize());
        cfg.gameDir = isDefault ? "" : path.toString();
        cfg.save();
        dirField.setText(cfg.gameDirPath().toString());
        onGameDirChanged.run();
    }

    private void moveDir(Path from, Path to, TextField dirField) {
        ProgressWindow pw = new ProgressWindow(getScene().getWindow(), t("folder.moving"));
        pw.show();
        Path cfgFile = Config.file().toAbsolutePath().normalize();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                int skipped = FolderMover.move(from, to,
                        p -> p.toAbsolutePath().normalize().equals(cfgFile) || isOpenLog(p), pw::status, pw::progress);
                Platform.runLater(() -> {
                    pw.close();
                    applyDir(to, dirField);
                    if (skipped > 0) {
                        Dlg.alert(Alert.AlertType.INFORMATION, skipped + " " + t("folder.skipped")).showAndWait();
                    }
                });
            } catch (Exception ex) {
                Log.error("Move failed", ex);
                Platform.runLater(() -> {
                    pw.close();
                    Alert al = Dlg.alert(Alert.AlertType.ERROR, ex.getMessage());
                    al.setHeaderText(t("failed"));
                    al.showAndWait();
                });
            }
        });
    }

    /** The launcher's own log file is open right now, so it stays where it is. */
    private static boolean isOpenLog(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith("launcher-") || n.endsWith(".lck");
    }

    // ---------- helpers ----------
    private static void open(Path p) {
        Dlg.browse(p);
    }

    /** Deletes leftover half-downloaded *.part files. */
    private static String clearPartFiles(Path root) {
        int n = 0;
        if (!Files.isDirectory(root)) return "0 " + t("removed");
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> parts = s.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
            for (Path p : parts) {
                try {
                    Files.deleteIfExists(p);
                    n++;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return n + " " + t("removed");
    }
}
