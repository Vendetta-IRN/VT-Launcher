package vt.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Parallel downloader (virtual threads) with SHA1 verification and retries. */
public final class Downloader {
    /**
     * url = first address to try. fallbacks = other addresses for the same file, tried when the first one
     * answers "not found" or is down (used for maven libraries that can live on more than one server).
     */
    public record Job(String url, Path path, String sha1, List<String> fallbacks) {
        public Job(String url, Path path, String sha1) {
            this(url, path, sha1, List.of());
        }
    }

    private Downloader() {}

    public static String sha1(Path p) throws IOException {
        return digest(p, "SHA-1");
    }

    public static String digest(Path p, String algo) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Skips the file if it already exists with the right hash; otherwise downloads and verifies it. */
    public static void fetch(Job j) throws IOException, InterruptedException {
        boolean check = j.sha1() != null && !j.sha1().isEmpty();
        if (Files.exists(j.path()) && (!check || sha1(j.path()).equalsIgnoreCase(j.sha1()))) return;
        Files.createDirectories(j.path().getParent());
        Path tmp = j.path().resolveSibling(j.path().getFileName() + ".part");

        List<String> urls = new ArrayList<>();
        urls.add(j.url());
        if (j.fallbacks() != null) {
            for (String f : j.fallbacks()) if (!urls.contains(f)) urls.add(f);
        }

        IOException last = new IOException("unknown error");
        for (String url : urls) {
            int tries = urls.size() > 1 ? 2 : 4;
            for (int i = 0; i < tries; i++) {
                try {
                    Net.download(url, tmp);
                    if (check && !sha1(tmp).equalsIgnoreCase(j.sha1())) {
                        last = new IOException("sha1 mismatch for " + url);
                        continue;
                    }
                    Files.move(tmp, j.path(), StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (Net.HttpStatus e) {
                    last = e;
                    if (e.status == 404 || e.status == 400 || e.status == 403) break; // not on this server: next one
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        Files.deleteIfExists(tmp);
        throw last;
    }

    /** Returns the list of failed downloads (empty = everything OK). */
    public static List<String> run(List<Job> jobs, BiConsumer<Integer, Integer> progress) {
        AtomicInteger done = new AtomicInteger();
        Queue<String> failed = new ConcurrentLinkedQueue<>();
        Semaphore limit = new Semaphore(16);
        int total = jobs.size();
        progress.accept(0, total);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Job j : jobs) {
                ex.submit(() -> {
                    try {
                        limit.acquire();
                        try {
                            fetch(j);
                        } finally {
                            limit.release();
                        }
                    } catch (Exception e) {
                        failed.add(j.url() + " : " + e.getMessage());
                    }
                    progress.accept(done.incrementAndGet(), total);
                });
            }
        }
        return new ArrayList<>(failed);
    }
}
