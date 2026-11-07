package vt.launcher;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import vt.launcher.core.Config;
import vt.launcher.core.Log;
import vt.launcher.core.Manifest;
import vt.launcher.core.Net;
import vt.launcher.ui.Chrome;
import vt.launcher.ui.Dlg;
import vt.launcher.ui.I18n;
import vt.launcher.ui.MainView;
import vt.launcher.ui.Motion;

public class App extends Application {
    private static Config config;
    private static Chrome chrome;

    /** Builds the whole interface again (used when the language changes). */
    public static void rebuildUi() {
        I18n.set(config.language);
        MainView mv = new MainView(config);
        orient(mv);
        chrome.setContent(mv);
    }

    private static void orient(javafx.scene.Node n) {
        n.setNodeOrientation(I18n.rtl() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
    }

    @Override
    public void start(Stage stage) {
        Config cfg = Config.load();
        config = cfg;
        I18n.set(cfg.language);
        Log.init(cfg.vtDir().resolve("logs"));
        Net.mirror = cfg.useMirror;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        stage.initStyle(StageStyle.TRANSPARENT);
        MainView root = new MainView(cfg);
        orient(root);
        chrome = new Chrome(stage, root);

        String css = App.class.getResource("/vt/style.css").toExternalForm();
        Scene scene = new Scene(chrome.root(), 1100, 700);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(css);
        chrome.attach(scene);
        Motion.install(scene);
        Dlg.init(stage, css);

        stage.setTitle("VT Launcher");
        var icon = App.class.getResourceAsStream("/vt/logo.png");
        if (icon != null) stage.getIcons().add(new Image(icon));
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
        Manifest.preload();
        Thread.ofVirtual().start(() -> vt.launcher.core.JavaRuntime.detect(false)); // so PLAY does not wait for it
    }

    @Override
    public void stop() {
        System.exit(0); // also stops the local skin server thread
    }
}
