package vt.launcher.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Watches a running game: reads its log while it loads, turns that into a progress value for the launcher's loading
 * screen, and reports when the window opens and when the game ends.
 */
public final class GameMonitor {
    private record Mark(Pattern pattern, double floor, String label) {
        Mark(String regex, double floor, String label) {
            this(Pattern.compile(regex), floor, label);
        }
    }

    // what the game writes while it starts, in the order it usually happens (modern and old versions)
    private static final List<Mark> MARKS = List.of(
            new Mark("Loading Minecraft .* with (Fabric|Quilt)", 0.55, "Starting the mod loader..."),
            new Mark("ModLauncher .*starting|Forge Mod Loader version|NeoForge|cpw\\.mods\\.modlauncher", 0.55, "Starting the mod loader..."),
            new Mark("Loading \\d+ mods|Found mod file|Mod list|Loading mod", 0.62, "Loading mods..."),
            new Mark("Mixin|mixin", 0.66, "Preparing mods..."),
            new Mark("Setting user:|Minecraft Version|Launching target 'fml", 0.72, "Starting Minecraft..."),
            new Mark("LWJGL Version|Backend library: LWJGL", 0.80, "Opening the game window..."),
            new Mark("Reloading ResourceManager", 0.88, "Loading textures and sounds..."),
            new Mark("Created: \\d+x\\d+", 0.92, "Building textures..."),
            new Mark("OpenAL initialized|Sound engine started", 0.97, "Almost ready..."));

    /** These lines are only written once the window exists, so they also mean "the game is open". */
    private static final Pattern READY = Pattern.compile(
            "Reloading ResourceManager|Created: \\d+x\\d+|OpenAL initialized|Sound engine started");

    private static final long GIVE_UP_MS = 5 * 60_000L;

    private GameMonitor() {}

    public static void start(Config cfg, GameLauncher.Started s, LaunchListener l) {
        AtomicBoolean ready = new AtomicBoolean();
        Thread.ofPlatform().daemon().name("vt-game-monitor").start(() -> run(cfg, s, l, ready));
    }

    private static void run(Config cfg, GameLauncher.Started s, LaunchListener l, AtomicBoolean ready) {
        long begin = System.currentTimeMillis();
        long pos = 0;
        int lines = 0;
        double floor = 0.46;
        String stage = "Starting Java...";
        String detail = "";
        double lastSent = -2;
        String lastDetail = "";
        String lastStage = "";
        StringBuilder partial = new StringBuilder();

        // the helper also finds the window directly (and gives it the launcher icon)
        WindowIcon.watch(cfg, s.process(), () -> {
            if (ready.compareAndSet(false, true)) l.windowOpened();
        });

        while (true) {
            boolean alive = s.process().isAlive();

            // new log text
            try (RandomAccessFile f = new RandomAccessFile(s.log().toFile(), "r")) {
                long len = f.length();
                if (len > pos) {
                    byte[] buf = new byte[(int) Math.min(len - pos, 1 << 20)];
                    f.seek(pos);
                    int n = f.read(buf);
                    if (n > 0) {
                        pos += n;
                        partial.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        int nl;
                        while ((nl = partial.indexOf("\n")) >= 0) {
                            String line = partial.substring(0, nl).replace("\r", "").trim();
                            partial.delete(0, nl + 1);
                            if (line.isEmpty()) continue;
                            lines++;
                            detail = line.length() > 110 ? line.substring(0, 110) + "..." : line;
                            for (Mark m : MARKS) {
                                if (m.floor() > floor && m.pattern().matcher(line).find()) {
                                    floor = m.floor();
                                    stage = m.label();
                                }
                            }
                            if (!ready.get() && READY.matcher(line).find() && ready.compareAndSet(false, true)) {
                                l.windowOpened();
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // the log file is not there yet
            }

            if (!alive) {
                l.exited(exitCode(s.process()), ready.get());
                return;
            }

            if (!ready.get()) {
                double byLines = 0.46 + 0.45 * (1 - Math.exp(-lines / 300.0));
                double f = Math.min(0.98, Math.max(floor, byLines));
                if (lines == 0) {
                    stage = "Starting Java...";
                    f = 0.46 + Math.min(0.04, (System.currentTimeMillis() - begin) / 60_000.0);
                }
                if (f != lastSent || !detail.equals(lastDetail) || !stage.equals(lastStage)) {
                    l.progress(stage, detail, f);
                    lastSent = f;
                    lastDetail = detail;
                    lastStage = stage;
                }
                if (System.currentTimeMillis() - begin > GIVE_UP_MS && ready.compareAndSet(false, true)) {
                    l.windowOpened(); // it is taking very long; do not keep the loading screen forever
                }
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static int exitCode(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }
}
