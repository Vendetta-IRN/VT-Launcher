package vt.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;

/**
 * The launcher's own window frame: title bar, minimize / maximize / fullscreen / close, rounded corners, resizing.
 * Maximize and fullscreen are done by animating the window bounds (no real fullscreen mode switch),
 * so going in and out is smooth and never flickers.
 */
public final class Chrome {
    private enum Mode { NORMAL, MAX, FULL }

    private static final double RADIUS = 16;
    private static final double EDGE = 6;
    private static final String ICON_MAX = "M5 5 H19 V19 H5 Z";
    private static final String ICON_RESTORE = "M8 8 H19 V19 H8 Z M5 15 V5 H15";

    private final Stage stage;
    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final Rectangle clip = new Rectangle();
    private final SVGPath maxIcon = new SVGPath();
    private Mode mode = Mode.NORMAL;
    private double rx;
    private double ry;
    private double rw;
    private double rh;
    private double dragX;
    private double dragY;
    private Timeline anim;

    public Chrome(Stage stage, Node content) {
        this.stage = stage;

        Label title = new Label("VT Launcher");
        title.getStyleClass().add("window-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button min = winButton("M5 12 H19");
        Button max = winButton(ICON_MAX);
        maxIcon.setContent(ICON_MAX);
        maxIcon.getStyleClass().add("win-icon");
        max.setGraphic(maxIcon);
        Button full = winButton("M4 9 V4 H9 M15 4 H20 V9 M20 15 V20 H15 M9 20 H4 V15");
        Button close = winButton("M5 5 L19 19 M19 5 L5 19");
        close.getStyleClass().add("win-close");

        HBox bar = new HBox(4, title, sp, min, full, max, close);
        bar.getStyleClass().add("window-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 8, 0, 16));
        bar.setMinHeight(40);
        bar.setPrefHeight(40);
        bar.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT); // window buttons stay on the right

        frame.setCenter(content);
        frame.setTop(bar);
        root.getChildren().add(frame);
        root.getStyleClass().add("window-root");

        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        root.setClip(clip);
        updateShape();

        bar.setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && mode == Mode.NORMAL) {
                stage.setX(e.getScreenX() - dragX);
                stage.setY(e.getScreenY() - dragY);
            }
        });
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)) {
                toggleMax();
            }
        });

        min.setOnAction(e -> stage.setIconified(true));
        max.setOnAction(e -> toggleMax());
        full.setOnAction(e -> toggleFull());
        close.setOnAction(e -> {
            stage.close();
            Platform.exit();
        });
    }

    public StackPane root() {
        return root;
    }

    /** Replaces the window content (used when the language changes). */
    public void setContent(Node content) {
        frame.setCenter(content);
    }

    /** Call once the scene exists: F11 / Esc for fullscreen and drag-to-resize on the edges. */
    public void attach(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F11 || (e.getCode() == KeyCode.ESCAPE && mode == Mode.FULL)) {
                toggleFull();
                e.consume();
            }
        });

        int[] zone = {0};
        double[] start = new double[6];
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> scene.setCursor(canResize() ? cursorFor(zoneOf(e, scene)) : Cursor.DEFAULT));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            zone[0] = canResize() ? zoneOf(e, scene) : 0;
            if (zone[0] != 0) {
                start[0] = e.getScreenX();
                start[1] = e.getScreenY();
                start[2] = stage.getX();
                start[3] = stage.getY();
                start[4] = stage.getWidth();
                start[5] = stage.getHeight();
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            int z = zone[0];
            if (z == 0) return;
            double dx = e.getScreenX() - start[0];
            double dy = e.getScreenY() - start[1];
            double x = start[2];
            double y = start[3];
            double w = start[4];
            double h = start[5];
            double minW = Math.max(300, stage.getMinWidth());
            double minH = Math.max(300, stage.getMinHeight());
            if ((z & 1) != 0) {
                double nw = Math.max(minW, w - dx);
                x = x + (w - nw);
                w = nw;
            }
            if ((z & 2) != 0) w = Math.max(minW, w + dx);
            if ((z & 4) != 0) {
                double nh = Math.max(minH, h - dy);
                y = y + (h - nh);
                h = nh;
            }
            if ((z & 8) != 0) h = Math.max(minH, h + dy);
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> zone[0] = 0);
    }

    private boolean canResize() {
        return mode == Mode.NORMAL;
    }

    private static int zoneOf(MouseEvent e, Scene scene) {
        double x = e.getSceneX();
        double y = e.getSceneY();
        int z = 0;
        if (x < EDGE) z |= 1;
        if (x > scene.getWidth() - EDGE) z |= 2;
        if (y < EDGE) z |= 4;
        if (y > scene.getHeight() - EDGE) z |= 8;
        return z;
    }

    private static Cursor cursorFor(int z) {
        return switch (z) {
            case 1, 2 -> Cursor.H_RESIZE;
            case 4, 8 -> Cursor.V_RESIZE;
            case 5, 10 -> Cursor.NW_RESIZE;
            case 6, 9 -> Cursor.NE_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    // ---------- maximize / fullscreen (animated bounds) ----------
    private Screen currentScreen() {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    private void rememberNormal() {
        if (mode == Mode.NORMAL) {
            rx = stage.getX();
            ry = stage.getY();
            rw = stage.getWidth();
            rh = stage.getHeight();
        }
    }

    private void toggleMax() {
        if (mode == Mode.NORMAL) {
            rememberNormal();
            Rectangle2D vb = currentScreen().getVisualBounds();
            mode = Mode.MAX;
            updateShape();
            animateTo(vb.getMinX(), vb.getMinY(), vb.getWidth(), vb.getHeight());
        } else {
            restore();
        }
    }

    private void toggleFull() {
        if (mode == Mode.FULL) {
            restore();
        } else {
            rememberNormal();
            Rectangle2D b = currentScreen().getBounds();
            mode = Mode.FULL;
            updateShape();
            animateTo(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
        }
    }

    private void restore() {
        mode = Mode.NORMAL;
        updateShape();
        animateTo(rx, ry, rw, rh);
    }

    private void animateTo(double tx, double ty, double tw, double th) {
        if (anim != null) anim.stop();
        double sx = stage.getX();
        double sy = stage.getY();
        double sw = stage.getWidth();
        double sh = stage.getHeight();
        DoubleProperty p = new SimpleDoubleProperty(0);
        p.addListener((o, a, b) -> {
            double f = b.doubleValue();
            stage.setWidth(sw + (tw - sw) * f);
            stage.setHeight(sh + (th - sh) * f);
            stage.setX(sx + (tx - sx) * f);
            stage.setY(sy + (ty - sy) * f);
        });
        anim = new Timeline(new KeyFrame(Duration.millis(180), new KeyValue(p, 1, Interpolator.EASE_BOTH)));
        anim.setOnFinished(e -> {
            stage.setX(tx);
            stage.setY(ty);
            stage.setWidth(tw);
            stage.setHeight(th);
        });
        anim.play();
    }

    private void updateShape() {
        double r = mode == Mode.NORMAL ? RADIUS : 0;
        clip.setArcWidth(r * 2);
        clip.setArcHeight(r * 2);
        maxIcon.setContent(mode == Mode.MAX ? ICON_RESTORE : ICON_MAX);
    }

    /** A flat icon button for window controls (also used by dialogs). */
    static Button winButton(String svgPath) {
        SVGPath p = new SVGPath();
        p.setContent(svgPath);
        p.getStyleClass().add("win-icon");
        Button b = new Button();
        b.setGraphic(p);
        b.getStyleClass().add("win-btn");
        b.setFocusTraversable(false);
        return b;
    }
}
