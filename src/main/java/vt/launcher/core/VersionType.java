package vt.launcher.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Works out what a version json is: Vanilla, Fabric, Forge, NeoForge, Quilt, OptiFine... */
public final class VersionType {
    private VersionType() {}

    public static List<String> detect(JsonObject v) {
        StringBuilder libsB = new StringBuilder();
        if (v.has("libraries")) {
            for (JsonElement e : v.getAsJsonArray("libraries")) {
                JsonObject l = e.getAsJsonObject();
                if (l.has("name")) libsB.append(l.get("name").getAsString().toLowerCase()).append(' ');
            }
        }
        String libs = libsB.toString();
        String main = v.has("mainClass") ? v.get("mainClass").getAsString().toLowerCase() : "";
        String id = v.has("id") ? v.get("id").getAsString().toLowerCase() : "";
        String args = (v.has("minecraftArguments") ? v.get("minecraftArguments").getAsString() : "").toLowerCase();
        if (v.has("arguments") && v.getAsJsonObject("arguments").has("game")) {
            args += " " + v.getAsJsonObject("arguments").get("game").toString().toLowerCase();
        }

        List<String> tags = new ArrayList<>();
        if (libs.contains("net.neoforged:") || id.contains("neoforge")) {
            tags.add("NeoForge");
        } else if (libs.contains("net.minecraftforge:forge") || libs.contains("net.minecraftforge:fmlloader")
                || libs.contains("net.minecraftforge:minecraftforge") || args.contains("fmltweaker")
                || args.contains("--launchtarget forge") || id.contains("forge")) {
            tags.add("Forge");
        }
        if (libs.contains("net.fabricmc:fabric-loader") || main.contains("fabricmc")) tags.add("Fabric");
        if (libs.contains("org.quiltmc:quilt-loader") || main.contains("quiltmc")) tags.add("Quilt");
        if (libs.contains("optifine:optifine") || libs.contains("optifine:launchwrapper-of")
                || args.contains("optifine") || id.contains("optifine")) tags.add("OptiFine");
        if (libs.contains("com.mumfrey:liteloader")) tags.add("LiteLoader");
        if (tags.isEmpty()) tags.add(v.has("inheritsFrom") ? "Modified" : "Vanilla");
        return tags;
    }

    public static String label(JsonObject v) {
        return String.join(" + ", detect(v));
    }
}
