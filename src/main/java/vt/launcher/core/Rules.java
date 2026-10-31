package vt.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.regex.Pattern;

/** Evaluates the "rules" arrays used by Mojang version JSON (libraries and arguments). Windows 64-bit only. */
public final class Rules {
    private Rules() {}

    public static boolean allowed(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null) return true;
        boolean ok = false;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            if (matches(r, features)) ok = r.get("action").getAsString().equals("allow");
        }
        return ok;
    }

    private static boolean matches(JsonObject r, Map<String, Boolean> features) {
        if (r.has("os")) {
            JsonObject os = r.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equals("windows")) return false;
            if (os.has("arch")) return false; // arch rules are for 32-bit x86; we run 64-bit only
            if (os.has("version")) {
                String ver = System.getProperty("os.version", "");
                if (!Pattern.compile(os.get("version").getAsString()).matcher(ver).find()) return false;
            }
        }
        if (r.has("features")) {
            for (Map.Entry<String, JsonElement> f : r.getAsJsonObject("features").entrySet()) {
                boolean have = features.getOrDefault(f.getKey(), false);
                if (have != f.getValue().getAsBoolean()) return false;
            }
        }
        return true;
    }
}
