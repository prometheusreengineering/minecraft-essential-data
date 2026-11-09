package studio.dreamys.prometheus.essential.recorder;

import gg.essential.lib.gson.JsonArray;
import gg.essential.lib.gson.JsonElement;
import gg.essential.lib.gson.JsonNull;
import gg.essential.lib.gson.JsonObject;
import gg.essential.lib.gson.JsonParser;
import gg.essential.lib.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns Essential's own packet log ({@code <global Essential dir>/infra-logs/<time>.log}, one JSON object per line:
 * {@code {"type": "SEND"|"RECV", "name": ..., "payload": ..., "id": ...}}, after an {@code OPEN} line with the protocol
 * version) into the recording cm-replay serves. The recording is published, so:
 * <ul>
 *   <li>only the cosmetics side of the session is kept: no chat, friends, profile, media or anything else;</li>
 *   <li>what the account owns and wears is replaced: no unlocked cosmetics, one blank outfit (default Steve skin),
 *       one blank emote wheel.</li>
 * </ul>
 */
final class Recording {
    /** The attachment name; the record workflow looks for it in the report. */
    static final String FILE = "recording.jsonl";
    /** Packet names (relative to {@code gg.essential.connectionmanager.common.packet}) kept in the recording. */
    private static final String[] KEEP_PREFIXES = {"cosmetic.", "wardrobe.", "features.ServerDisabledFeaturesPacket",
            "currency.", "coins.ServerCoinBundleOptionsPacket", "coins.ClientCoinBundleOptionsPacket"};

    static final String UNLOCKED = "cosmetic.ServerCosmeticsUserUnlockedPacket";
    static final String OUTFITS = "cosmetic.outfit.ServerCosmeticOutfitPopulatePacket";
    static final String EMOTE_WHEELS = "cosmetic.emote.ServerCosmeticEmoteWheelPopulatePacket";
    /** Minecraft's default Steve skin, so the blank outfit has a valid one. */
    static final String STEVE = "1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b";

    private Recording() {}

    static boolean keeps(String name) {
        // The replay server negotiates its own type ids and sends its own keep-alives.
        if (name == null || name.startsWith("connection.")) return false;
        for (String p : KEEP_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    /**
     * Keeps the {@code OPEN} line, the kept client requests, and server packets that are either kept or answer a kept
     * request, with the account's own packets neutralized. Lines that aren't JSON objects are dropped.
     */
    @SuppressWarnings("deprecation") // Essential's Gson may predate the static parse methods
    static List<String> filter(List<String> log) {
        List<String> out = new ArrayList<>();
        Set<String> keptRequests = new HashSet<>();
        JsonParser parser = new JsonParser();
        for (String line : log) {
            JsonObject o;
            try {
                o = parser.parse(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            String type = string(o, "type");
            String name = string(o, "name");
            String id = string(o, "id");
            if ("OPEN".equals(type)) {
                out.add(line);
            } else if ("SEND".equals(type) && keeps(name)) {
                if (!id.isEmpty()) keptRequests.add(id);
                out.add(line);
            } else if ("RECV".equals(type) && !name.startsWith("connection.")
                    && (keeps(name) || (!id.isEmpty() && keptRequests.contains(id)))) {
                out.add(neutralize(o, name) ? o.toString() : line);
            }
        }
        return out;
    }

    /** Replaces the account's own data in a server packet; returns whether it changed anything. */
    static boolean neutralize(JsonObject packet, String name) {
        if (!packet.has("payload") || !packet.get("payload").isJsonObject()) return false;
        JsonObject payload = packet.getAsJsonObject("payload");
        switch (name) {
            case UNLOCKED -> packet.add("payload", blank(payload));
            case OUTFITS -> replaceFirst(payload, "outfits", outfit -> {
                outfit.addProperty("a", "1");
                outfit.addProperty("b", "Outfit #1");
                if (outfit.has("c")) outfit.addProperty("c", "0;" + STEVE); // skinTexture: model;hash
                if (outfit.has("f")) outfit.addProperty("f", true); // selected
                if (outfit.has("j")) outfit.addProperty("j", "1"); // skinId
            });
            case EMOTE_WHEELS -> replaceFirst(payload, "a", wheel -> {
                wheel.addProperty("a", "1");
                if (wheel.has("b")) wheel.addProperty("b", true); // selected
            });
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Keeps one entry of the array, blanked and then given the fixed values; an empty array stays empty. */
    private static void replaceFirst(JsonObject payload, String key, java.util.function.Consumer<JsonObject> fill) {
        JsonArray list = new JsonArray();
        JsonElement recorded = payload.get(key);
        if (recorded != null && recorded.isJsonArray() && recorded.getAsJsonArray().size() > 0
                && recorded.getAsJsonArray().get(0).isJsonObject()) {
            JsonObject one = blank(recorded.getAsJsonArray().get(0).getAsJsonObject());
            fill.accept(one);
            list.add(one);
        }
        payload.add(key, list);
    }

    /** The same keys with empty values: [] , {}, "", 0, false. */
    static JsonObject blank(JsonObject o) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) out.add(e.getKey(), blankValue(e.getValue()));
        return out;
    }

    private static JsonElement blankValue(JsonElement e) {
        if (e.isJsonArray()) return new JsonArray();
        if (e.isJsonObject()) return new JsonObject();
        if (e.isJsonNull()) return JsonNull.INSTANCE;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return new JsonPrimitive(false);
        if (p.isNumber()) return new JsonPrimitive(0);
        return new JsonPrimitive("");
    }

    /** The newest {@code .log} in Essential's {@code infra-logs} folder (older ones are gzipped), or null. */
    static Path newestLog(Path infraLogs) throws IOException {
        if (!Files.isDirectory(infraLogs)) return null;
        Path newest = null;
        try (Stream<Path> files = Files.list(infraLogs)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".log")) continue;
                if (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = p;
            }
        }
        return newest;
    }

    static byte[] record(Path log) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : filter(Files.readAllLines(log, StandardCharsets.UTF_8))) sb.append(line).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
