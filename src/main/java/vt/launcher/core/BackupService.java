package vt.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backs up the worlds (saves), options.txt and servers.dat of the shared game folder and of every
 * version that has its own folder, into one zip. The newest few backups are kept.
 */
public final class BackupService {
    public static final int KEEP = 5;
    private static final Pattern ALLOWED = Pattern.compile("^(versions/[^/]+/)?(saves/.+|options\\.txt|servers\\.dat)$");

    private BackupService() {}

    public static Path dir(Config cfg) {
        return cfg.vtDir().resolve("backups");
    }

    /** Game folders to back up, as (path, prefix inside the zip). */
    private static List<Path[]> locations(Config cfg) {
        Path root = cfg.gameDirPath();
        List<Path[]> out = new ArrayList<>();
        out.add(new Path[]{root, Path.of("")});
        Path versions = root.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (Stream<Path> s = Files.list(versions)) {
                for (Path v : s.filter(Files::isDirectory).toList()) {
                    String id = v.getFileName().toString();
                    if (cfg.isolated(id)) out.add(new Path[]{v, Path.of("versions", id)});
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    public static Path create(Config cfg, BiConsumer<Integer, Integer> progress) throws IOException {
        List<Path[]> files = new ArrayList<>(); // {file, entry name as Path}
        for (Path[] loc : locations(cfg)) {
            Path base = loc[0];
            Path saves = base.resolve("saves");
            if (Files.isDirectory(saves)) {
                try (Stream<Path> s = Files.walk(saves)) {
                    for (Path f : s.filter(Files::isRegularFile).toList()) {
                        if (f.getFileName().toString().equals("session.lock")) continue;
                        files.add(new Path[]{f, loc[1].resolve(base.relativize(f).toString())});
                    }
                }
            }
            for (String n : new String[]{"options.txt", "servers.dat"}) {
                if (Files.exists(base.resolve(n))) files.add(new Path[]{base.resolve(n), loc[1].resolve(n)});
            }
        }
        if (files.isEmpty()) throw new IOException("Nothing to back up (no worlds found).");

        Path dir = dir(cfg);
        Files.createDirectories(dir);
        Path zip = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        int done = 0;
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path[] f : files) {
                String entry = f[1].toString().replace('\\', '/');
                try {
                    z.putNextEntry(new ZipEntry(entry));
                    Files.copy(f[0], z);
                    z.closeEntry();
                } catch (IOException e) {
                    Log.warn("Backup skipped " + entry + ": " + e.getMessage()); // e.g. a file the game has open
                }
                progress.accept(++done, files.size());
            }
        }
        prune(dir);
        return zip;
    }

    public static List<Path> list(Config cfg) {
        Path dir = dir(cfg);
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** Makes a safety backup of the current worlds first, then extracts the chosen backup over them. */
    public static void restore(Config cfg, Path zip) throws IOException {
        try {
            create(cfg, (a, b) -> {
            });
        } catch (IOException ignored) {
            // nothing to back up yet
        }
        Path root = cfg.gameDirPath().toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !ALLOWED.matcher(name).matches()) continue;
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) continue; // zip-slip guard
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void prune(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.list(dir)) {
            all = s.filter(f -> f.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = KEEP; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }
}
