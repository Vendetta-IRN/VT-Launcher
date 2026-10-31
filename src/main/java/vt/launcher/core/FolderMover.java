package vt.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Moves the contents of the game folder to a new place. Existing files at the destination are never overwritten. */
public final class FolderMover {
    private FolderMover() {}

    /** Returns how many files were skipped because they already existed at the destination. */
    public static int move(Path from, Path to, Predicate<Path> skip, Consumer<String> status,
                           BiConsumer<Integer, Integer> progress) throws IOException {
        Path f = from.toAbsolutePath().normalize();
        Path t = to.toAbsolutePath().normalize();
        if (t.startsWith(f) || f.startsWith(t)) {
            throw new IOException("The new folder cannot be inside the old one (or the other way round).");
        }
        status.accept("Counting files...");
        List<Path> files;
        try (Stream<Path> s = Files.walk(f)) {
            files = s.filter(Files::isRegularFile).filter(p -> !skip.test(p)).toList();
        }
        status.accept("Moving " + files.size() + " files...");
        int total = files.size();
        int done = 0;
        int skipped = 0;
        progress.accept(0, total);
        for (Path p : files) {
            Path target = t.resolve(f.relativize(p).toString());
            if (Files.exists(target)) {
                skipped++;
            } else {
                Files.createDirectories(target.getParent());
                Files.move(p, target);
            }
            done++;
            if (done % 20 == 0 || done == total) progress.accept(done, total);
        }
        // remove directories that are now empty (Files.delete refuses non-empty ones)
        try (Stream<Path> s = Files.walk(f)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(f) && Files.isDirectory(p)) {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return skipped;
    }
}
