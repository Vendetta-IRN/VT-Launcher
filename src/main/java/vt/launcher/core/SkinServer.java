package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * Tiny local Yggdrasil-style server used together with authlib-injector so the game shows a local skin/cape.
 * Only answers for the one player of the profile being launched.
 */
public final class SkinServer implements AutoCloseable {
    private final HttpServer server;
    private final String uuid;
    private final String name;
    private final byte[] skin;
    private final byte[] cape;
    private final String skinHash;
    private final String capeHash;
    private final boolean slim;

    public SkinServer(String uuid, String name, byte[] skin, boolean slim, byte[] cape) throws IOException {
        this.uuid = uuid.replace("-", "").toLowerCase();
        this.name = name;
        this.skin = skin;
        this.cape = cape;
        this.slim = slim;
        this.skinHash = skin == null ? null : sha1(skin);
        this.capeHash = cape == null ? null : sha1(cape);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "vt-skin");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    private static String sha1(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                JsonObject meta = new JsonObject();
                meta.addProperty("serverName", "VT Launcher");
                meta.addProperty("implementationName", "VT Launcher");
                meta.addProperty("implementationVersion", "1.0");
                JsonArray domains = new JsonArray();
                domains.add("127.0.0.1");
                domains.add("localhost");
                JsonObject root = new JsonObject();
                root.add("meta", meta);
                root.add("skinDomains", domains);
                send(ex, 200, "application/json", root.toString().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/sessionserver/session/minecraft/profile/")) {
                String id = path.substring(path.lastIndexOf('/') + 1).replace("-", "").toLowerCase();
                if (id.equals(uuid)) {
                    send(ex, 200, "application/json", profile(true).toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    send(ex, 204, null, null);
                }
            } else if (path.equals("/api/profiles/minecraft")) {
                JsonArray out = new JsonArray();
                try {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    for (JsonElement n : JsonParser.parseString(body).getAsJsonArray()) {
                        if (n.getAsString().equalsIgnoreCase(name)) out.add(profile(false));
                    }
                } catch (Exception ignored) {
                }
                send(ex, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/textures/")) {
                String h = path.substring("/textures/".length()).replace(".png", "");
                byte[] data = h.equals(skinHash) ? skin : h.equals(capeHash) ? cape : null;
                if (data == null) send(ex, 404, null, null);
                else send(ex, 200, "image/png", data);
            } else {
                send(ex, 404, null, null);
            }
        } catch (Exception e) {
            Log.warn("Skin server error: " + e.getMessage());
            try {
                send(ex, 500, null, null);
            } catch (Exception ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private JsonObject profile(boolean withTextures) {
        JsonObject p = new JsonObject();
        p.addProperty("id", uuid);
        p.addProperty("name", name);
        if (withTextures) {
            JsonObject textures = new JsonObject();
            if (skin != null) {
                JsonObject s = new JsonObject();
                s.addProperty("url", url() + "/textures/" + skinHash + ".png");
                if (slim) {
                    JsonObject m = new JsonObject();
                    m.addProperty("model", "slim");
                    s.add("metadata", m);
                }
                textures.add("SKIN", s);
            }
            if (cape != null) {
                JsonObject c = new JsonObject();
                c.addProperty("url", url() + "/textures/" + capeHash + ".png");
                textures.add("CAPE", c);
            }
            JsonObject tex = new JsonObject();
            tex.addProperty("timestamp", System.currentTimeMillis());
            tex.addProperty("profileId", uuid);
            tex.addProperty("profileName", name);
            tex.add("textures", textures);
            String b64 = Base64.getEncoder().encodeToString(tex.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject prop = new JsonObject();
            prop.addProperty("name", "textures");
            prop.addProperty("value", b64);
            JsonArray props = new JsonArray();
            props.add(prop);
            p.add("properties", props);
        }
        return p;
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        if (type != null) ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
