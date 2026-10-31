package vt.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class Log {
    private static final Logger L = Logger.getLogger("vt");

    private Log() {}

    public static void init(Path dir) {
        try {
            Files.createDirectories(dir);
            String pattern = dir.resolve("launcher-%g.log").toString().replace('\\', '/');
            FileHandler fh = new FileHandler(pattern, 1_000_000, 3, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            L.addHandler(fh);
            L.setLevel(Level.INFO);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> error("Uncaught in " + t.getName(), e));
    }

    public static void info(String m) { L.info(m); }

    public static void warn(String m) { L.warning(m); }

    public static void error(String m, Throwable t) { L.log(Level.SEVERE, m, t); }
}
