package vt.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/** The front of the head (with the hat layer) from a skin, scaled up sharply. Gray face if there is no skin. */
public final class SkinFace {
    private SkinFace() {}

    public static Image face(Image skin, int size) {
        int[] px = new int[64];
        boolean ok = skin != null && (int) skin.getWidth() == 64 && skin.getPixelReader() != null
                && ((int) skin.getHeight() == 64 || (int) skin.getHeight() == 32);
        if (ok) {
            PixelReader r = skin.getPixelReader();
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int base = r.getArgb(8 + x, 8 + y);
                    int hat = r.getArgb(40 + x, 8 + y);
                    px[y * 8 + x] = (hat >>> 24) > 0 ? hat : base;
                }
            }
        } else {
            for (int i = 0; i < 64; i++) px[i] = 0xFF7D7D86;
            px[3 * 8 + 2] = 0xFF2A2A30;
            px[3 * 8 + 5] = 0xFF2A2A30;
            px[5 * 8 + 3] = 0xFF2A2A30;
            px[5 * 8 + 4] = 0xFF2A2A30;
        }
        int f = Math.max(1, size / 8);
        WritableImage out = new WritableImage(8 * f, 8 * f);
        PixelWriter w = out.getPixelWriter();
        for (int y = 0; y < 8 * f; y++) {
            for (int x = 0; x < 8 * f; x++) w.setArgb(x, y, px[(y / f) * 8 + (x / f)]);
        }
        return out;
    }
}
