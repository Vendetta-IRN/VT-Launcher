package vt.launcher.core;

import java.io.InputStream;
import java.util.Properties;

/** The app version. It comes from the build (the same number as the GitHub release). */
public final class AppInfo {
    private static String version;

    private AppInfo() {}

    public static synchronized String version() {
        if (version == null) {
            version = "dev";
            try (InputStream in = AppInfo.class.getResourceAsStream("/vt/app.properties")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(in);
                    String v = p.getProperty("version");
                    if (v != null && !v.isBlank() && !v.contains("$")) version = v.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return version;
    }
}
