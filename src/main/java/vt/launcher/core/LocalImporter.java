package vt.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Imports a Minecraft version (or a whole .minecraft/versions folder) from the disk into our game folder. */
public final class LocalImporter {
    public record Candidate(String id, Path dir, Path json, Path jar, String type, boolean installed) {}

    /** sourceRoot = the original .minecraft folder if we can find it (so its libraries can be reused), else null. */
    public record Scan(Path sourceRoot, List<Candidate> items) {}

    private LocalImporter() {}

    public static Scan scan(Path picked, Path targetRoot) throws IOException {
        List<Candidate> items = new ArrayList<>();
        Path sourceRoot = null;
        Path versionsDir = null;

        if (Files.isDirectory(picked.resolve("versions"))) {
            versionsDir = picked.resolve("versions");
            sourceRoot = picked;
        } else if (picked.getFileName() != null && picked.getFileName().toString().equalsIgnoreCase("versions")) {
            versionsDir = picked;
            sourceRoot = picked.getParent();
        }

        if (versionsDir != null) {
            try (Stream<Path> s = Files.list(versionsDir)) {
                for (Path d : s.filter(Files::isDirectory).sorted().toList()) addIfVersion(d, items, targetRoot);
            }
        } else {
            addIfVersion(picked, items, targetRoot);
            Path parent = picked.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().equalsIgnoreCase("versions")) {
                sourceRoot = parent.getParent();
            }
        }
        return new Scan(sourceRoot, items);
    }

    private static void addIfVersion(Path dir, List<Candidate> items, Path targetRoot) {
        String id = dir.getFileName().toString();
        Path json = dir.resolve(id + ".json");
        if (!Files.exists(json)) json = firstWithExt(dir, ".json");
        if (json == null) return;
        try {
            JsonObject v = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!v.has("mainClass") && !v.has("inheritsFrom")) return;
            String base = json.getFileName().toString();
            base = base.substring(0, base.length() - 5);
            Path jar = dir.resolve(base + ".jar");
            if (!Files.exists(jar)) jar = dir.resolve(id + ".jar");
            if (!Files.exists(jar)) jar = firstWithExt(dir, ".jar");
            boolean installed = Files.exists(targetRoot.resolve("versions").resolve(id).resolve(id + ".json"));
            items.add(new Candidate(id, dir, json, jar, VersionType.label(v), installed));
        } catch (Exception ignored) {
            // not a version json
        }
    }

    private static Path firstWithExt(Path dir, String ext) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(ext))
                    .sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Copies the version (and its parent / libraries when available), then downloads whatever is still missing.
     * Returns the id it was installed under.
     */
    public static String importVersion(Candidate cand, Scan scan, Path root,
                                       Consumer<String> status, BiConsumer<Integer, Integer> progress) throws Exception {
        // an existing version is never replaced: the import gets a new name, "name (1)"
        String newId = VersionManager.uniqueId(root, cand.id());
        Candidate c = new Candidate(newId, cand.dir(), cand.json(), cand.jar(), cand.type(), false);
        Path dest = root.resolve("versions").resolve(c.id());

        status.accept("Copying " + c.id() + "...");
        copyVersion(c, dest);

        JsonObject raw = JsonParser.parseString(Files.readString(dest.resolve(c.id() + ".json"), StandardCharsets.UTF_8))
                .getAsJsonObject();

        // parent version (vanilla) that this one is built on
        if (raw.has("inheritsFrom")) {
            String parent = raw.get("inheritsFrom").getAsString();
            Path parentJson = root.resolve("versions").resolve(parent).resolve(parent + ".json");
            if (!Files.exists(parentJson)) {
                Path srcParent = scan.sourceRoot() == null ? null : scan.sourceRoot().resolve("versions").resolve(parent);
                if (srcParent != null && Files.exists(srcParent.resolve(parent + ".json"))) {
                    status.accept("Copying parent " + parent + "...");
                    Path pj = srcParent.resolve(parent + ".json");
                    Path pjar = srcParent.resolve(parent + ".jar");
                    copyVersion(new Candidate(parent, srcParent, pj, Files.exists(pjar) ? pjar : null, "Vanilla", false),
                            root.resolve("versions").resolve(parent));
                } else {
                    installParentFromMojang(parent, root, status, progress);
                }
            }
        }

        // reuse libraries from the original .minecraft (needed for Forge/NeoForge patched jars)
        if (scan.sourceRoot() != null && Files.isDirectory(scan.sourceRoot().resolve("libraries"))) {
            status.accept("Copying libraries...");
            JsonObject merged = VersionJson.load(root, c.id());
            for (String rel : VersionInstaller.libraryPaths(merged)) {
                Path src = scan.sourceRoot().resolve("libraries").resolve(rel);
                Path dst = root.resolve("libraries").resolve(rel);
                if (Files.exists(src) && !Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        }

        VersionInstaller.ensureFiles(c.id(), root, status, progress);
        return c.id();
    }

    private static void installParentFromMojang(String parent, Path root, Consumer<String> status,
                                                BiConsumer<Integer, Integer> progress) throws Exception {
        status.accept("Downloading parent version " + parent + "...");
        for (Manifest.VersionEntry v : Manifest.load()) {
            if (v.id().equals(parent)) {
                VersionInstaller.install(parent, v.url(), root, status, progress);
                return;
            }
        }
        throw new IOException("Parent version '" + parent + "' was not found on Mojang's list. "
                + "Pick the .minecraft folder that contains it so it can be copied.");
    }

    /** Copies every file of the version folder; the json and jar are renamed to match the folder name. */
    private static void copyVersion(Candidate c, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (Stream<Path> s = Files.walk(c.dir())) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                Path rel = c.dir().relativize(p);
                String name = rel.toString();
                Path target;
                if (p.equals(c.json())) target = dest.resolve(c.id() + ".json");
                else if (c.jar() != null && p.equals(c.jar())) target = dest.resolve(c.id() + ".jar");
                else target = dest.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
