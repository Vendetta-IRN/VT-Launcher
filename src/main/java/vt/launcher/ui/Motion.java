package vt.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/** Smooth press / hover animation for buttons and a fade for page changes. */
public final class Motion {
    private static final String KEY = "vt.motion";

    private Motion() {}

    public static void install(Scene scene) {
        // pressing a button shrinks it a little and it springs back; it never grows, so nothing gets clipped
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Node b = button(e.getTarget());
            if (b != null && !b.isDisabled()) scale(b, 0.95, 90);
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            Node b = button(e.getTarget());
            if (b != null) scale(b, 1.0, 180);
        });
    }

    private static Node button(Object target) {
        Node n = target instanceof Node node ? node : null;
        while (n != null) {
            if (n instanceof Button || n instanceof ToggleButton) return n;
            n = n.getParent();
        }
        return null;
    }

    private static void scale(Node n, double to, int ms) {
        Object old = n.getProperties().get(KEY);
        if (old instanceof Timeline t) t.stop();
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(n.scaleXProperty(), to, Interpolator.EASE_BOTH),
                new KeyValue(n.scaleYProperty(), to, Interpolator.EASE_BOTH)));
        n.getProperties().put(KEY, tl);
        tl.play();
    }

    /** Fades a page in and slides it up a little. */
    public static void pageIn(Node page) {
        page.setOpacity(0);
        page.setTranslateY(10);
        FadeTransition f = new FadeTransition(Duration.millis(220), page);
        f.setToValue(1);
        f.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition t = new TranslateTransition(Duration.millis(220), page);
        t.setToY(0);
        t.setInterpolator(Interpolator.EASE_OUT);
        f.play();
        t.play();
    }
}
