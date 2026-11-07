package vt.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** HTTP helper. If a Mojang/Forge/Fabric server fails, it switches to the BMCLAPI mirror automatically. */
public final class Net {
    private static final String BM = "https://bmclapi2.bangbang93.com";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static volatile boolean mirror = false;

    /** HTTP error with a status code. 400/404 mean "not found", not "server blocked", so no mirror switch. */
    public static final class HttpStatus extends IOException {
        public final int status;

        HttpStatus(int status, String url) {
            super("HTTP " + status + " " + url);
            this.status = status;
        }

        boolean notFound() {
            return status == 404 || status == 400;
        }
    }

    private Net() {}

    /** The mirror address of a url, or the same url if the mirror does not host it. */
    public static String toMirror(String url) {
        URI u = URI.create(url);
        String host = u.getHost();
        String path = u.getRawPath();
        if (host == null) return url;
        if (host.equals("libraries.minecraft.net")) return BM + "/maven" + path;
        if (host.equals("resources.download.minecraft.net")) return BM + "/assets" + path;
        if (host.equals("authlib-injector.yushi.moe")) return BM + "/mirrors/authlib-injector" + path;
        if (host.equals("meta.fabricmc.net")) return BM + "/fabric-meta" + path;
        if (host.equals("meta.quiltmc.org")) return BM + "/quilt-meta" + path;
        if (host.equals("maven.quiltmc.org")) {
            // Quilt's repository lives under /repository/release, the mirror's /maven root does not have that prefix
            // (with the old mapping every Quilt library was a 404 as soon as the mirror was used).
            return BM + "/maven" + path.replaceFirst("^/repository/(release|snapshot)", "");
        }
        if (host.equals("maven.fabricmc.net") || host.equals("maven.minecraftforge.net")) {
            return BM + "/maven" + path;
        }
        if (host.equals("maven.neoforged.net")) return BM + "/maven" + path.replaceFirst("^/releases", "");
        if (host.endsWith("mojang.com")) return BM + path;
        return url;
    }

    public static String mir(String url) {
        return mirror ? toMirror(url) : url;
    }

    private static boolean worthMirror(String url, IOException e) {
        if (mirror) return false;
        if (e instanceof HttpStatus h && h.notFound()) return false;
        return !toMirror(url).equals(url); // other hosts have no mirror, do not switch
    }

    private static HttpRequest req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "VTLauncher/1.2 (unofficial Minecraft launcher)")
                .build();
    }

    private static byte[] get(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = CLIENT.send(req(url), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new HttpStatus(res.statusCode(), url);
        return res.body();
    }

    private static void dl(String url, Path target) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = CLIENT.send(req(url), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new HttpStatus(res.statusCode(), url);
        }
        try (InputStream in = res.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        try {
            return get(mir(url));
        } catch (IOException e) {
            if (!worthMirror(url, e)) throw e;
            Log.warn("Server unreachable, switching to mirror: " + e.getMessage());
            mirror = true;
            return get(mir(url));
        }
    }

    public static void download(String url, Path target) throws IOException, InterruptedException {
        try {
            dl(mir(url), target);
        } catch (IOException e) {
            if (!worthMirror(url, e)) throw e;
            Log.warn("Server unreachable, switching to mirror: " + e.getMessage());
            mirror = true;
            dl(mir(url), target);
        }
    }
}
