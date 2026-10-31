package vt.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;

/** Rotating 3D Minecraft player model (drag with the mouse to turn it). */
public final class SkinPreview extends StackPane {
    private final Group modelHolder = new Group();
    private final Rotate rx = new Rotate(-8, Rotate.X_AXIS);
    private final Rotate ry = new Rotate(25, Rotate.Y_AXIS);
    private final boolean supported;
    private AnimationTimer timer;
    private double lastX;
    private double lastY;
    private boolean dragging;

    public SkinPreview(double w, double h) {
        setMinSize(w, h);
        setPrefSize(w, h);
        setMaxSize(w, h);
        getStyleClass().add("preview-box");

        supported = Platform.isSupported(ConditionalFeature.SCENE3D);
        if (!supported) {
            getChildren().add(new Label("3D preview is not supported on this PC"));
            return;
        }

        modelHolder.setTranslateY(-8);
        Group stage = new Group(modelHolder);
        stage.getTransforms().addAll(rx, ry);
        Group root = new Group(stage, new AmbientLight(Color.WHITE));

        SubScene sub = new SubScene(root, w, h, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.TRANSPARENT);
        PerspectiveCamera cam = new PerspectiveCamera(true);
        cam.setNearClip(0.1);
        cam.setFarClip(1000);
        cam.setTranslateZ(-85);
        sub.setCamera(cam);
        getChildren().add(sub);

        sub.setOnMousePressed(e -> {
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            dragging = true;
        });
        sub.setOnMouseDragged(e -> {
            ry.setAngle(ry.getAngle() + (e.getSceneX() - lastX) * 0.8);
            rx.setAngle(Math.max(-40, Math.min(40, rx.getAngle() - (e.getSceneY() - lastY) * 0.5)));
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        sub.setOnMouseReleased(e -> dragging = false);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!dragging) ry.setAngle(ry.getAngle() + 0.5);
            }
        };
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) timer.stop();
            else timer.start();
        });
        setSkin(null, false, null);
    }

    /** skin == null shows a gray placeholder. cape may be null. */
    public void setSkin(Image skinImg, boolean slim, Image capeImg) {
        if (!supported) return;
        modelHolder.getChildren().clear();

        Image skin = skinImg == null ? null : normalize(skinImg);
        boolean custom = skin != null;
        if (skin == null) skin = placeholder();

        PhongMaterial mat = new PhongMaterial(Color.WHITE);
        mat.setDiffuseMap(upscale(skin, 8));
        double arm = slim ? 3 : 4;

        // base layer
        part(8, 8, 8, 0, 0, 0, -4, 0, 0, mat);
        part(8, 12, 4, 16, 16, 0, 6, 0, 0, mat);
        part(arm, 12, 4, 40, 16, -(4 + arm / 2), 6, 0, 0, mat);
        part(arm, 12, 4, 32, 48, (4 + arm / 2), 6, 0, 0, mat);
        part(4, 12, 4, 0, 16, -2, 18, 0, 0, mat);
        part(4, 12, 4, 16, 48, 2, 18, 0, 0, mat);

        // overlay layer (hat, jacket, sleeves, pants)
        if (custom) {
            part(8, 8, 8, 32, 0, 0, -4, 0, 0.5, mat);
            part(8, 12, 4, 16, 32, 0, 6, 0, 0.25, mat);
            part(arm, 12, 4, 40, 32, -(4 + arm / 2), 6, 0, 0.25, mat);
            part(arm, 12, 4, 48, 48, (4 + arm / 2), 6, 0, 0.25, mat);
            part(4, 12, 4, 0, 32, -2, 18, 0, 0.25, mat);
            part(4, 12, 4, 0, 48, 2, 18, 0, 0.25, mat);
        }

        if (capeImg != null && (int) capeImg.getWidth() == 64 && (int) capeImg.getHeight() == 32) {
            PhongMaterial cm = new PhongMaterial(Color.WHITE);
            cm.setDiffuseMap(upscale(capeImg, 8));
            MeshView cape = box(10, 16, 1, 0, 0, 64, 32, 0, cm);
            cape.getTransforms().add(new Rotate(180, Rotate.Y_AXIS));
            cape.setTranslateY(8);
            cape.setTranslateZ(2.6);
            modelHolder.getChildren().add(cape);
        }
    }

    // ---------- model parts ----------
    private void part(double w, double h, double d, int u, int v, double cx, double cy, double cz,
                      double inflate, PhongMaterial mat) {
        MeshView mv = box(w, h, d, u, v, 64, 64, inflate, mat);
        mv.setTranslateX(cx);
        mv.setTranslateY(cy);
        mv.setTranslateZ(cz);
        modelHolder.getChildren().add(mv);
    }

    /** A box using Minecraft's skin texture layout. The model front faces -Z (towards the camera). */
    private static MeshView box(double w, double h, double d, int u, int v, int tw, int th,
                                double g, PhongMaterial mat) {
        float hx = (float) (w / 2 + g);
        float hy = (float) (h / 2 + g);
        float hz = (float) (d / 2 + g);
        int iw = (int) w;
        int ih = (int) h;
        int id = (int) d;

        List<Float> pts = new ArrayList<>();
        List<Float> tex = new ArrayList<>();
        // each face: TL, TR, BR, BL corners + uv rectangle (x, y, width, height)
        face(pts, tex, tw, th, new float[][]{{-hx, -hy, -hz}, {hx, -hy, -hz}, {hx, hy, -hz}, {-hx, hy, -hz}}, u + id, v + id, iw, ih);             // front
        face(pts, tex, tw, th, new float[][]{{hx, -hy, hz}, {-hx, -hy, hz}, {-hx, hy, hz}, {hx, hy, hz}}, u + 2 * id + iw, v + id, iw, ih);         // back
        face(pts, tex, tw, th, new float[][]{{-hx, -hy, hz}, {-hx, -hy, -hz}, {-hx, hy, -hz}, {-hx, hy, hz}}, u, v + id, id, ih);                   // right
        face(pts, tex, tw, th, new float[][]{{hx, -hy, -hz}, {hx, -hy, hz}, {hx, hy, hz}, {hx, hy, -hz}}, u + id + iw, v + id, id, ih);             // left
        face(pts, tex, tw, th, new float[][]{{-hx, -hy, hz}, {hx, -hy, hz}, {hx, -hy, -hz}, {-hx, -hy, -hz}}, u + id, v, iw, id);                   // top
        face(pts, tex, tw, th, new float[][]{{-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz}}, u + id + iw, v, iw, id);                  // bottom

        TriangleMesh mesh = new TriangleMesh();
        float[] p = new float[pts.size()];
        for (int i = 0; i < p.length; i++) p[i] = pts.get(i);
        float[] t = new float[tex.size()];
        for (int i = 0; i < t.length; i++) t[i] = tex.get(i);
        mesh.getPoints().addAll(p);
        mesh.getTexCoords().addAll(t);
        for (int q = 0; q < 6; q++) {
            int b = q * 4;
            mesh.getFaces().addAll(b, b, b + 1, b + 1, b + 2, b + 2,
                    b, b, b + 2, b + 2, b + 3, b + 3);
        }
        MeshView mv = new MeshView(mesh);
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    private static void face(List<Float> pts, List<Float> tex, int tw, int th, float[][] c,
                             int x, int y, int wi, int he) {
        for (float[] p : c) {
            pts.add(p[0]);
            pts.add(p[1]);
            pts.add(p[2]);
        }
        float x0 = (float) x / tw;
        float x1 = (float) (x + wi) / tw;
        float y0 = (float) y / th;
        float y1 = (float) (y + he) / th;
        tex.add(x0); tex.add(y0);
        tex.add(x1); tex.add(y0);
        tex.add(x1); tex.add(y1);
        tex.add(x0); tex.add(y1);
    }

    // ---------- images ----------
    /** 64x64 stays; legacy 64x32 skins get their left limbs mirrored from the right ones. Others -> null. */
    private static Image normalize(Image img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        if (w != 64) return null;
        if (h == 64) return img;
        if (h != 32) return null;
        WritableImage out = new WritableImage(64, 64);
        PixelReader r = img.getPixelReader();
        PixelWriter wr = out.getPixelWriter();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) wr.setArgb(x, y, r.getArgb(x, y));
        }
        limb(r, wr, 0, 16, 16, 48);   // right leg -> left leg
        limb(r, wr, 40, 16, 32, 48);  // right arm -> left arm
        return out;
    }

    private static void flip(PixelReader r, PixelWriter w, int sx, int sy, int wd, int ht, int dx, int dy) {
        for (int y = 0; y < ht; y++) {
            for (int x = 0; x < wd; x++) w.setArgb(dx + (wd - 1 - x), dy + y, r.getArgb(sx + x, sy + y));
        }
    }

    private static void limb(PixelReader r, PixelWriter w, int sx, int sy, int dx, int dy) {
        flip(r, w, sx + 4, sy, 4, 4, dx + 4, dy);          // top
        flip(r, w, sx + 8, sy, 4, 4, dx + 8, dy);          // bottom
        flip(r, w, sx + 4, sy + 4, 4, 12, dx + 4, dy + 4); // front
        flip(r, w, sx + 12, sy + 4, 4, 12, dx + 12, dy + 4); // back
        flip(r, w, sx, sy + 4, 4, 12, dx + 8, dy + 4);     // right side -> left side
        flip(r, w, sx + 8, sy + 4, 4, 12, dx, dy + 4);     // left side -> right side
    }

    private static Image placeholder() {
        WritableImage img = new WritableImage(64, 64);
        PixelWriter w = img.getPixelWriter();
        int gray = 0xFF7D7D86;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) w.setArgb(x, y, gray);
        }
        return img;
    }

    /** Nearest-neighbour upscale so the pixels stay sharp on the 3D model. */
    private static Image upscale(Image src, int f) {
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        WritableImage out = new WritableImage(w * f, h * f);
        PixelReader r = src.getPixelReader();
        PixelWriter wr = out.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = r.getArgb(x, y);
                for (int dy = 0; dy < f; dy++) {
                    for (int dx = 0; dx < f; dx++) wr.setArgb(x * f + dx, y * f + dy, argb);
                }
            }
        }
        return out;
    }
}
