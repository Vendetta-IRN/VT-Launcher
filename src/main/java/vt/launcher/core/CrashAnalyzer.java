package vt.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns "the game closed with code 1" into something a person can act on: what most likely went wrong and what to do.
 * It reads the game log (and the newest crash report) and looks for the well known causes.
 */
public final class CrashAnalyzer {
    /** title = one short line, why = what happened, fix = what to do. */
    public record Diagnosis(String title, String why, String fix) {}

    /** Everything the error window shows. */
    public record Report(Diagnosis diagnosis, String details, Path log, Path crashReport, int exitCode) {}

    private CrashAnalyzer() {}

    public static Report analyze(int exitCode, Path log, Path instanceDir, long startedMillis,
                                 int javaNeeded, String javaUsed, int ramMb, boolean beforeWindow) {
        List<String> lines = tail(log, 400);
        String text = String.join("\n", lines);
        Path crash = newestCrashReport(instanceDir, startedMillis);
        if (crash != null) {
            try {
                text += "\n" + String.join("\n", Files.readAllLines(crash, StandardCharsets.UTF_8).stream().limit(60).toList());
            } catch (Exception ignored) {
            }
        }
        Diagnosis d = diagnose(exitCode, text, javaNeeded, javaUsed, ramMb, beforeWindow);

        StringBuilder det = new StringBuilder();
        det.append("Exit code: ").append(exitCode).append('\n');
        det.append("Java: ").append(javaUsed == null ? "?" : javaUsed).append("  (needs ").append(javaNeeded).append(")\n");
        det.append("Memory: ").append(ramMb).append(" MB\n");
        if (crash != null) det.append("Crash report: ").append(crash).append('\n');
        if (log != null) det.append("Log: ").append(log).append('\n');
        det.append("\n----- last lines of the game log -----\n");
        int from = Math.max(0, lines.size() - 70);
        for (String l : lines.subList(from, lines.size())) det.append(l).append('\n');
        return new Report(d, det.toString(), log, crash, exitCode);
    }

    /** For failures that happen before the game process exists (Java missing, files missing, invalid Java...). */
    public static Report fromException(Throwable ex, Path logDir) {
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        Diagnosis d;
        String m = msg.toLowerCase();
        if (m.contains("error=193") || m.contains("not a valid win32")) {
            d = new Diagnosis("The Java file is not valid",
                    "Windows refused to run the selected Java (it is damaged, or it is a 32-bit / different-system Java).",
                    "Remove the imported Java in Settings, or install a 64-bit Java. The launcher can also download the right one by itself.");
        } else if (m.contains("error=2") || m.contains("cannot find the file")) {
            d = new Diagnosis("Java was not found",
                    "The Java file that was chosen no longer exists (it was moved, uninstalled or removed by an antivirus).",
                    "Press PLAY again. If it still fails, open Settings > Java and press Rescan.");
        } else if (m.contains("error=5") || m.contains("access is denied")) {
            d = new Diagnosis("Windows blocked Java",
                    "Access to the Java file was denied, usually by an antivirus or by folder permissions.",
                    "Allow VT Launcher and its Java folder in your antivirus, or move the game folder somewhere you own.");
        } else if (m.contains("needs java")) {
            d = new Diagnosis("The right Java is missing", msg.split("\n")[0], "See the details below.");
        } else if (m.contains("files are missing") || m.contains("missing library")) {
            d = new Diagnosis("Game files are missing",
                    "Some files of this version were deleted or never finished downloading.",
                    "Open the Versions page, select this version and press Repair.");
        } else if (m.contains("unknownhost") || m.contains("connect") || m.contains("timed out") || m.contains("http ")) {
            d = new Diagnosis("A download failed",
                    "The launcher needed to download something before starting and could not reach the server.",
                    "Check your internet connection. If Mojang servers are blocked for you, tick the mirror option in Settings.");
        } else {
            d = new Diagnosis("The game could not be started", firstLine(msg), "See the details below, or open the launcher log.");
        }
        Path logFile = latest(logDir, "launcher-");
        StringBuilder det = new StringBuilder(msg).append("\n\n");
        StackTraceElement[] st = ex.getStackTrace();
        det.append(ex.getClass().getName()).append('\n');
        for (int i = 0; i < Math.min(8, st.length); i++) det.append("   at ").append(st[i]).append('\n');
        return new Report(d, det.toString(), logFile, null, -1);
    }

    // ---------- the known causes ----------
    private static Diagnosis diagnose(int code, String text, int javaNeeded, String javaUsed, int ramMb, boolean beforeWindow) {
        Matcher cls = Pattern.compile("class file version (\\d+)").matcher(text);
        if (text.contains("UnsupportedClassVersionError") || cls.find()) {
            String need = "a newer Java";
            Matcher m2 = Pattern.compile("class file version (\\d+)").matcher(text);
            if (m2.find()) need = "Java " + (Integer.parseInt(m2.group(1)) - 44) + " or newer";
            return new Diagnosis("The game or a mod needs a newer Java",
                    "Something that is loaded was built for " + need + ", but the game started with " + (javaUsed == null ? "an older one" : javaUsed) + ".",
                    "A mod may be made for a different Minecraft version. Remove the mod that was added last, or use a version that matches your mods.");
        }
        if (text.contains("Could not reserve enough space") || text.contains("Invalid maximum heap size")
                || text.contains("The specified size exceeds the maximum representable size")
                || text.contains("There is insufficient memory for the Java Runtime")) {
            return new Diagnosis("Too much memory was requested",
                    "Java could not reserve " + ramMb + " MB of RAM. Your PC does not have that much free, or the Java is 32-bit.",
                    "Open Settings and move the memory slider lower (for example 2048 to 4096 MB), close other programs, and try again.");
        }
        if (text.contains("java.lang.OutOfMemoryError") || text.contains("GC overhead limit exceeded")) {
            return new Diagnosis("The game ran out of memory",
                    "It was given " + ramMb + " MB and needed more.",
                    "Open Settings and drag the memory slider higher (heavy mod packs need 4096 MB or more). Remove some mods if your PC has little RAM.");
        }
        if (text.contains("Unrecognized VM option") || text.contains("Unrecognized option")
                || text.contains("Could not create the Java Virtual Machine")) {
            return new Diagnosis("Java did not accept the Java arguments",
                    "One of the extra Java arguments in Settings is not understood by this Java.",
                    "Open Settings > Performance > Java arguments and press Default.");
        }
        if (text.contains("Incompatible mods found") || text.contains("Mod resolution failed")
                || text.contains("Some of your mods are incompatible") || text.contains("DependencyException")
                || text.contains("Missing or unsupported mandatory dependencies") || text.contains("requires any version of")
                || text.contains("MissingModsException") || text.contains("ModResolutionException")) {
            return new Diagnosis("A mod is missing a dependency or does not fit",
                    "The mod loader stopped because mods need something that is not installed, or they conflict with each other.",
                    "Read the lines below that name the mod (they say 'requires' or 'incompatible'). Install the missing mod, or remove that mod, then play again.");
        }
        if (text.contains("InaccessibleObjectException") || text.contains("does not \"opens")
                || text.contains("module java.base does not")) {
            return new Diagnosis("This version does not work with this Java",
                    "The game or its loader needs an older Java than the one it started with (" + (javaUsed == null ? "?" : javaUsed) + ").",
                    "The launcher normally picks Java " + javaNeeded + " by itself. Remove any imported Java in Settings > Java so it can choose again.");
        }
        if (text.contains("GLFW error") || text.contains("WGL:") || text.contains("Failed to create window")
                || text.contains("Pixel format not accelerated") || text.contains("Could not create context")
                || text.contains("OpenGL") && text.contains("not supported")) {
            return new Diagnosis("The graphics card could not open the game window",
                    "Windows reported an OpenGL / graphics driver problem.",
                    "Update your graphics driver (NVIDIA, AMD or Intel). On laptops, make sure the game runs on the dedicated GPU. Turn off screen overlays.");
        }
        if (text.contains("UnsatisfiedLinkError") || text.contains("Failed to load library") || text.contains("lwjgl") && text.contains("Failed to locate")) {
            return new Diagnosis("A game library could not be loaded",
                    "Native files of the game (LWJGL) are missing or were removed, often by an antivirus.",
                    "Open the Versions page, select this version and press Repair. Allow the game folder in your antivirus.");
        }
        if (text.contains("NoClassDefFoundError") || text.contains("ClassNotFoundException") || text.contains("Mixin apply failed")
                || text.contains("MixinTransformerError") || text.contains("InvalidInjectionException")) {
            return new Diagnosis("A mod does not match this game version",
                    "A mod tried to use code that does not exist in this Minecraft version or loader.",
                    "Check that every mod is made for this exact Minecraft version and loader. Remove the mod named in the lines below.");
        }
        if (code == -1073740791 || code == -1073741819 || code == -1073740940 || text.contains("EXCEPTION_ACCESS_VIOLATION")
                || text.contains("hs_err_pid")) {
            return new Diagnosis("The game crashed hard (native crash)",
                    "Java itself crashed. This is almost always the graphics driver, an overlay (Discord, Steam, GPU overlays) or a broken mod.",
                    "Update the graphics driver, turn off overlays and recorders, and try without mods.");
        }
        if (text.contains("Exception in thread \"main\"") || text.contains("Caused by:")) {
            return new Diagnosis(beforeWindow ? "The game crashed while loading" : "The game crashed",
                    "Minecraft or a mod threw an error (exit code " + code + ").",
                    "The important part is the line that starts with 'Caused by' in the details below. Send that line to whoever made the mod, or remove the mod.");
        }
        if (beforeWindow) {
            return new Diagnosis("The game closed before its window opened",
                    "Minecraft exited with code " + code + " during loading, without a clear error message.",
                    "Open the log below. If it is empty, try again with the default Java arguments (Settings > Performance > Default).");
        }
        return new Diagnosis("The game closed with an error (code " + code + ")",
                "Minecraft stopped unexpectedly.", "Open the log and the crash report (if there is one) for the exact cause.");
    }

    // ---------- helpers ----------
    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static List<String> tail(Path file, int max) {
        List<String> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) return out;
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - max), all.size()));
        } catch (IOException e) {
            // not valid UTF-8 (Windows console encoding): read it leniently
            try {
                String s = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                String[] all = s.split("\\R");
                for (int i = Math.max(0, all.length - max); i < all.length; i++) out.add(all[i]);
            } catch (IOException ignored) {
            }
            return out;
        }
    }

    private static Path newestCrashReport(Path instance, long since) {
        if (instance == null) return null;
        Path dir = instance.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("crash-"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(Instant.ofEpochMilli(since - 2000));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path latest(Path dir, String prefix) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
