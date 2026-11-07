package vt.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lists every world on this PC (the shared game folder and each version that has its own folder) and
 * exports a single world as a zip. The zip holds the world folder, so it can be added again with
 * "Add a world" or unzipped straight into any saves folder.
 */
public final class WorldExport {
    /** where = "shared folder" or the version name; path = the world folder. */
    public record World(String name, String folder, String where, Path path, long size, String modified) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private WorldExport() {}

    public static List<World> list(Config cfg, String sharedLabel) {
        List<World> out = new ArrayList<>();
        Path root = cfg.gameDirPath();
        collect(out, root.resolve("saves"), sharedLabel);
        Path versions = root.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (Stream<Path> s = Files.list(versions)) {
                for (Path v : s.filter(Files::isDirectory).sorted().toList()) {
                    Path saves = v.resolve("saves");
                    if (Files.isDirectory(saves)) collect(out, saves, v.getFileName().toString());
                }
            } catch (IOException ignored) {
            }
        }
        out.sort(Comparator.comparing((World w) -> w.name().toLowerCase()));
        return out;
    }

    private static void collect(List<World> out, Path saves, String where) {
        if (!Files.isDirectory(saves)) return;
        try (Stream<Path> s = Files.list(saves)) {
            for (Path w : s.filter(Files::isDirectory).toList()) {
                Path level = w.resolve("level.dat");
                if (!Files.exists(level)) continue; // not a world
                String folder = w.getFileName().toString();
                String name = Nbt.levelName(level);
                if (name == null || name.isBlank()) name = folder;
                String when = "";
                try {
                    when = LocalDateTime.ofInstant(Files.getLastModifiedTime(level).toInstant(), ZoneId.systemDefault()).format(WHEN);
                } catch (IOException ignored) {
                }
                out.add(new World(name, folder, where, w, folderSize(w), when));
            }
        } catch (IOException ignored) {
        }
    }

    private static long folderSize(Path dir) {
        long n = 0;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                try {
                    n += Files.size(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return n;
    }

    /** Exports one world folder into destDir as "<name>-<date>.zip". Returns the zip. */
    public static Path export(Path world, String displayName, Path destDir, BiConsumer<Integer, Integer> progress)
            throws IOException {
        if (!Files.isDirectory(world)) throw new IOException("The world folder no longer exists: " + world);
        Files.createDirectories(destDir);

        String safe = displayName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = world.getFileName().toString();
        String base = safe + "-" + LocalDateTime.now().format(STAMP);
        Path zip = destDir.resolve(base + ".zip");
        for (int i = 1; Files.exists(zip); i++) zip = destDir.resolve(base + " (" + i + ").zip"); // never replace an earlier export

        List<Path> files;
        try (Stream<Path> s = Files.walk(world)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        String top = world.getFileName().toString();
        int done = 0;
        int skipped = 0;
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
            z.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Path f : files) {
                String rel = world.relativize(f).toString().replace('\\', '/');
                if (rel.equals("session.lock")) { // the game keeps this open while it runs; it is not needed
                    progress.accept(++done, files.size());
                    continue;
                }
                // open the file first: if the game has it locked, nothing is written for it
                try (java.io.InputStream in = Files.newInputStream(f)) {
                    z.putNextEntry(new ZipEntry(top + "/" + rel));
                    in.transferTo(z);
                    z.closeEntry();
                } catch (IOException e) {
                    skipped++;
                    Log.warn("World export skipped " + rel + ": " + e.getMessage());
                }
                progress.accept(++done, files.size());
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, zip);
        if (skipped > 0) Log.warn("World export: " + skipped + " files could not be read (the game was probably running)");
        return zip;
    }
}
