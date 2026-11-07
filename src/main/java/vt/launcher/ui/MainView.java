package vt.launcher.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import vt.launcher.core.AppInfo;
import vt.launcher.core.BackupService;
import vt.launcher.core.Config;
import vt.launcher.core.CrashAnalyzer;
import vt.launcher.core.LaunchListener;
import vt.launcher.core.GameLauncher;
import vt.launcher.core.GameMonitor;
import vt.launcher.core.Log;
import vt.launcher.core.Manifest;
import vt.launcher.core.Profile;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static vt.launcher.ui.I18n.t;

public class MainView extends StackPane {
    private final Config cfg;
    private final ObservableList<String> installed = FXCollections.observableArrayList();
    private final StackPane pages = new StackPane();
    private final ComboBox<String> versionBox = new ComboBox<>(installed);
    private final Button playBtn = new Button(t("play"));
    private final Label launchStatus = new Label();
    private final ProgressBar launchBar = new ProgressBar(-1);
    private final ProfileState state;
    private final ImageView face = new ImageView();
    private final Label playerName = new Label();
    private boolean syncing = false;
    private final LaunchOverlay overlay = new LaunchOverlay();
    private final HBox errorBar = new HBox(12);
    private final Label errorText = new Label();
    private CrashAnalyzer.Report lastReport;

    public MainView(Config cfg) {
        this.cfg = cfg;
        this.state = new ProfileState(cfg);
        getChildren().addAll(buildBackground(), buildLayout(), overlay);
        refreshInstalled();
    }

    // ---------- background (blurred red/black glow) ----------
    private Pane buildBackground() {
        Pane bg = new Pane();
        bg.setMouseTransparent(true);
        bg.getStyleClass().add("bg-layer");

        Circle a = new Circle(300, Color.web("#c1121f", 0.60));
        Circle b = new Circle(260, Color.web("#7a0a14", 0.55));
        Circle c = new Circle(220, Color.web("#3a0a12", 0.70));
        a.centerXProperty().bind(bg.widthProperty().multiply(0.12));
        a.centerYProperty().bind(bg.heightProperty().multiply(0.10));
        b.centerXProperty().bind(bg.widthProperty().multiply(0.95));
        b.centerYProperty().bind(bg.heightProperty().multiply(0.95));
        c.centerXProperty().bind(bg.widthProperty().multiply(0.60));
        c.centerYProperty().bind(bg.heightProperty().multiply(0.30));

        Group inner = new Group(a, b, c);
        inner.setEffect(new GaussianBlur(63));
        Group outer = new Group(inner);
        outer.setEffect(new GaussianBlur(63));
        outer.setCache(true); // the blur is drawn once, not on every frame
        outer.setCacheHint(CacheHint.SPEED);
        bg.getChildren().add(outer);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(bg.widthProperty());
        clip.heightProperty().bind(bg.heightProperty());
        bg.setClip(clip);
        return bg;
    }

    // ---------- main layout ----------
    private Node buildLayout() {
        // only Home is built at startup; the other pages are built the first time you open them
        HomePage homePage = new HomePage(installed, state);
        LazyPage home = new LazyPage(() -> homePage);
        LazyPage versions = new LazyPage(() -> new VersionsPage(cfg, installed, this::refreshInstalled));
        LazyPage profiles = new LazyPage(() -> new ProfilesPage(state));
        LazyPage settings = new LazyPage(() -> new SettingsPage(cfg, this::refreshInstalled));

        ToggleGroup group = new ToggleGroup();
        ToggleButton navHome = nav(t("nav.home"), "M3 11 L12 3 L21 11 M5 10 V21 H19 V10 M10 21 V14 H14 V21", group, home);
        ToggleButton navVersions = nav(t("nav.versions"), "M12 3 V15 M7 10 L12 15 L17 10 M4 20 H20", group, versions);
        ToggleButton navProfiles = nav(t("nav.profiles"), "M8 8 A4 4 0 1 0 16 8 A4 4 0 1 0 8 8 M4 21 C4 16 8 14 12 14 C16 14 20 16 20 21", group, profiles);
        ToggleButton navSettings = nav(t("nav.settings"), "M4 7 H20 M4 12 H20 M4 17 H20 M9 5 V9 M15 10 V14 M8 15 V19", group, settings);

        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            Node page = ((LazyPage) now.getUserData()).get();
            pages.getChildren().setAll(page);
            Motion.pageIn(page);
        });
        navHome.setSelected(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label ver = new Label("VT Launcher " + AppInfo.version());
        ver.getStyleClass().add("muted");

        VBox side = new VBox(14, buildAccount(), new Region(), navHome, navVersions, navProfiles, navSettings, spacer, ver);
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(230);
        side.setMinWidth(230);
        side.setMaxWidth(230);
        side.setPadding(new Insets(20, 16, 18, 16));

        // bottom-right: status + installed versions + play
        versionBox.setPromptText(t("no.versions"));
        versionBox.setPrefWidth(190);
        versionBox.valueProperty().addListener((o, old, now) -> {
            Profile cur = state.active.get();
            if (!syncing && now != null && cur != null) {
                cur.version = now;
                state.touch();
            }
        });
        // no dropdown arrow while there is nothing to pick
        versionBox.showingProperty().addListener((o, a, b) -> {
            if (b && installed.isEmpty()) versionBox.hide();
        });
        installed.addListener((ListChangeListener<String>) c -> updateArrow());
        updateArrow();

        playBtn.getStyleClass().add("play-button");
        playBtn.setOnAction(e -> play());

        launchStatus.getStyleClass().add("muted");
        launchBar.setPrefWidth(220);
        launchBar.setVisible(false);
        VBox statusBox = new VBox(4, launchStatus, launchBar);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusBox, Priority.ALWAYS);

        HBox bar = new HBox(12, statusBox, versionBox, playBtn);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(14, 26, 20, 26));
        bar.getStyleClass().add("bottom-bar");

        errorText.getStyleClass().add("error-bar-text");
        errorText.setWrapText(true);
        HBox.setHgrow(errorText, Priority.ALWAYS);
        Button errDetails = new Button(t("error.view"));
        errDetails.setOnAction(e -> {
            if (lastReport != null) ErrorDialog.show(lastReport);
        });
        Button errClose = new Button("x");
        errClose.setOnAction(e -> clearError());
        errorBar.getChildren().addAll(errorText, errDetails, errClose);
        errorBar.setAlignment(Pos.CENTER_LEFT);
        errorBar.getStyleClass().add("error-bar");
        errorBar.setPadding(new Insets(10, 14, 10, 16));
        errorBar.setVisible(false);
        errorBar.setManaged(false);
        VBox.setMargin(errorBar, new Insets(0, 26, 0, 26));

        VBox right = new VBox(pages, errorBar, bar);
        VBox.setVgrow(pages, Priority.ALWAYS);

        BorderPane main = new BorderPane();
        main.setLeft(side);
        main.setCenter(right);
        return main;
    }

    private void updateArrow() {
        boolean empty = installed.isEmpty();
        versionBox.getStyleClass().remove("no-arrow");
        if (empty) versionBox.getStyleClass().add("no-arrow");
    }

    /** Fixed (not editable) block: the player's face from the skin, and the player name from Profiles. */
    private Node buildAccount() {
        face.setFitWidth(48);
        face.setFitHeight(48);
        face.setSmooth(false);
        Rectangle clip = new Rectangle(48, 48);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        face.setClip(clip);

        playerName.getStyleClass().add("player-name");
        HBox.setHgrow(playerName, Priority.ALWAYS);
        HBox account = new HBox(12, face, playerName);
        account.setAlignment(Pos.CENTER_LEFT);

        state.active.addListener((o, a, b) -> syncFromProfile());
        state.updated.addListener((o, a, b) -> syncFromProfile());
        return account;
    }

    /** Fills the face, the player name and the version box from the active profile. */
    private void syncFromProfile() {
        Profile p = state.active.get();
        if (p == null) return;
        face.setImage(SkinFace.face(Skins.imageFor(cfg, p), 48)); // own skin, or the default Steve / Alex
        playerName.setText(p.player);

        syncing = true;
        if (installed.contains(p.version)) versionBox.setValue(p.version);
        else if (!installed.isEmpty()) versionBox.setValue(installed.get(0));
        else versionBox.setValue(null);
        syncing = false;
    }

    private ToggleButton nav(String text, String iconPath, ToggleGroup group, LazyPage page) {
        SVGPath icon = new SVGPath();
        icon.setContent(iconPath);
        icon.getStyleClass().add("icon");
        ToggleButton b = new ToggleButton(text, icon);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setToggleGroup(group);
        b.setUserData(page);
        return b;
    }

    // ---------- launching ----------
    private void setLaunching(boolean on) {
        playBtn.setDisable(on);
        versionBox.setDisable(on);
        launchBar.setVisible(false);
        if (!on) launchStatus.setText("");
    }

    private void clearError() {
        errorBar.setVisible(false);
        errorBar.setManaged(false);
    }

    /** Shows the error in a window and keeps a red bar above the Play button until the next launch. */
    private void showFailure(CrashAnalyzer.Report r) {
        lastReport = r;
        errorText.setText(r.diagnosis().title());
        errorBar.setManaged(true);
        errorBar.setVisible(true);
        ErrorDialog.show(r);
    }

    private void play() {
        Profile prof = state.active.get();
        String id = versionBox.getValue();
        if (id == null || prof == null) {
            Dlg.alert(Alert.AlertType.INFORMATION, t("pick.version")).showAndWait();
            return;
        }
        clearError();
        prof.version = id;
        state.save();
        Stage stage = (Stage) getScene().getWindow();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Process> proc = new AtomicReference<>();

        setLaunching(true);
        overlay.open(id, prof.player, () -> {
            cancelled.set(true);
            Process p = proc.get();
            if (p != null) p.destroyForcibly();
            overlay.close();
            setLaunching(false);
        });

        // one listener: preparation progress, then the game's own loading progress, then window / exit
        LaunchListener ui = new LaunchListener() {
            @Override
            public void progress(String stageText, String detail, double fraction) {
                Platform.runLater(() -> {
                    if (!cancelled.get()) overlay.update(stageText, detail, fraction);
                });
            }

            @Override
            public void windowOpened() {
                Platform.runLater(() -> {
                    if (!cancelled.get()) onWindowOpened(stage);
                });
            }
        };

        Thread.ofPlatform().daemon().start(() -> {
            try {
                GameLauncher.Started s = GameLauncher.launch(cfg, prof, ui);
                if (cancelled.get()) {
                    s.process().destroyForcibly();
                    return;
                }
                proc.set(s.process());
                GameMonitor.start(cfg, s, new LaunchListener() {
                    @Override
                    public void progress(String st, String d, double f) {
                        ui.progress(st, d, f);
                    }

                    @Override
                    public void windowOpened() {
                        ui.windowOpened();
                    }

                    @Override
                    public void exited(int code, boolean windowWasOpen) {
                        boolean wasCancelled = cancelled.get();
                        CrashAnalyzer.Report rep = null;
                        if (!wasCancelled && code != 0) {
                            rep = CrashAnalyzer.analyze(code, s.log(), s.instance(), s.startedMillis(),
                                    s.javaNeeded(), s.javaUsed(), s.ramMb(), !windowWasOpen);
                        }
                        CrashAnalyzer.Report r = rep;
                        Platform.runLater(() -> onGameExit(stage, wasCancelled, r));
                    }
                });
            } catch (Exception ex) {
                Log.error("Launch failed: " + id, ex);
                CrashAnalyzer.Report r = CrashAnalyzer.fromException(ex, cfg.vtDir().resolve("logs"));
                Platform.runLater(() -> {
                    overlay.close();
                    setLaunching(false);
                    if (!cancelled.get()) showFailure(r);
                });
            }
        });
    }

    /** The game window is open: loading is done. */
    private void onWindowOpened(Stage stage) {
        overlay.finish();
        launchStatus.setText(t("game.running"));
        String mode = cfg.afterLaunch == null ? "keep" : cfg.afterLaunch;
        // the launcher hosts the local skin server (own skin or default Steve/Alex), so it cannot close while the game runs
        if (mode.equals("close")) mode = "minimize";
        if (mode.equals("minimize")) stage.setIconified(true);
    }

    private void onGameExit(Stage stage, boolean cancelled, CrashAnalyzer.Report report) {
        overlay.close();
        setLaunching(false);
        if (stage.isIconified()) stage.setIconified(false);
        stage.toFront();
        if (cancelled) return;
        if (cfg.autoBackup) {
            Thread.ofVirtual().start(() -> {
                try {
                    BackupService.create(cfg, (a, b) -> {
                    });
                } catch (Exception ex) {
                    Log.warn("Auto backup failed: " + ex.getMessage());
                }
            });
        }
        if (report != null) showFailure(report);
    }

    /** A page that is created on first use. */
    private static final class LazyPage {
        private final Supplier<Node> make;
        private Node node;

        LazyPage(Supplier<Node> make) {
            this.make = make;
        }

        Node get() {
            if (node == null) node = make.get();
            return node;
        }
    }

    // ---------- state ----------
    public void refreshInstalled() {
        installed.setAll(Manifest.installed(cfg.gameDirPath()));
        syncFromProfile();
    }
}
