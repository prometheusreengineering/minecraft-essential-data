package studio.dreamys.prometheus.essential.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {
    private static String send(String name, String payload, String id) {
        return "{\"type\": \"SEND\", \"name\": \"" + name + "\", \"payload\": " + payload + ", \"id\": \"" + id + "\"}";
    }

    private static String recv(String name, String payload, String id) {
        return "{\"type\": \"RECV\", \"name\": \"" + name + "\", \"payload\": " + payload + ", \"id\": \"" + id + "\"}";
    }

    /** As Essential 1.5.2 sends them (two of the test account's outfits, shortened). */
    private static final String OUTFITS = recv(Recording.OUTFITS, "{\"outfits\": ["
            + "{\"a\": \"1718911\", \"b\": \"Outfit #1\", \"c\": \"0;3b84ce7ed1e35d08d803041e87ef687d509c592d6a5020144ad4341edf61bfc0\","
            + " \"j\": \"28426\", \"d\": {\"ICON\": \"BASE_ICON\"}, \"e\": {\"BASE_ICON\": []}, \"f\": false, \"g\": 1641872214176, \"i\": 1641872214176},"
            + "{\"a\": \"21603089\", \"b\": \"Dino Trooper\", \"c\": \"1;67ed7a985c45aaaa\", \"j\": \"2953918\", \"d\": {\"HAT\": \"DINO\"},"
            + " \"e\": {}, \"f\": true, \"g\": 1, \"i\": 2}]}", "");
    private static final String UNLOCKED = recv(Recording.UNLOCKED,
            "{\"a\": [\"2340c0e03dd24a11b15a8b33c2a7e9e3\", \"AFRO\"], \"b\": {\"AFRO\": 1}, \"d\": true}", "");
    private static final String WHEELS = recv(Recording.EMOTE_WHEELS, "{\"a\": ["
            + "{\"a\": \"6003943\", \"b\": true, \"c\": {\"0\": \"GRIDDY\"}, \"d\": 1679763946981, \"e\": 1791742267023},"
            + "{\"a\": \"6003948\", \"b\": false, \"c\": {}, \"d\": 1, \"e\": 2}]}", "");

    @Test
    void keepsCosmeticsAndDropsTheRestOfTheAccount() {
        String open = "{\"type\": \"OPEN\", \"version\": 12}";
        String cosmetics = recv("cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[]}", "");
        String wardrobe = recv("wardrobe.ServerWardrobeSettingsPacket", "{}", "");
        String disabled = recv("features.ServerDisabledFeaturesPacket", "{}", "");
        List<String> kept = Recording.filter(List.of(
                open,
                recv("connection.ConnectionRegisterPacketTypeIdPacket", "{\"a\":\"chat.X\",\"b\":1}", ""),
                send("connection.ConnectionRegisterPacketTypeIdPacket", "{\"a\":\"cosmetic.Y\",\"b\":1}", ""),
                recv("connection.ConnectionKeepAlivePacket", "{}", "k1"),
                send("connection.ConnectionKeepAlivePacket", "{}", "k1"),
                cosmetics, wardrobe, disabled,
                recv("chat.ServerChatChannelMessagePacket", "{\"a\":\"hi\"}", ""),
                recv("relationships.ServerRelationshipPopulatePacket", "{}", ""),
                recv("profile.ServerProfileStatusPacket", "{}", ""),
                recv("media.ServerMediaPopulatePacket", "{}", ""),
                recv("skin.ServerSkinPopulatePacket", "{}", ""),
                send("telemetry.ClientTelemetryPacket", "{}", "t1"),
                recv("response.ResponseActionPacket", "{}", "t1")));
        assertEquals(List.of(open, cosmetics, wardrobe, disabled), kept);
    }

    @Test
    void keepsRequestsAndTheirRepliesEvenUnderOtherNames() {
        String request = send("cosmetic.ClientCosmeticRequestPacket", "{\"a\":[\"X\"],\"b\":[]}", "r1");
        String reply = recv("cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[]}", "r1");
        String ack = send("wardrobe.ClientWardrobeSettingsPacket", "{}", "r2");
        String ackReply = recv("response.ResponseActionPacket", "{\"a\":true}", "r2");
        String otherReply = recv("response.ResponseActionPacket", "{\"a\":true}", "r3");
        assertEquals(List.of(request, reply, ack, ackReply), Recording.filter(List.of(request, reply, ack, ackReply, otherReply)));
    }

    @Test
    void replacesWhatTheAccountOwnsAndWears() {
        List<String> kept = Recording.filter(List.of(OUTFITS, UNLOCKED, WHEELS));
        assertEquals(3, kept.size());
        String all = String.join("\n", kept);
        for (String accountData : List.of("1718911", "21603089", "Dino Trooper", "3b84ce7ed1e3", "67ed7a985c45", "28426",
                "2953918", "BASE_ICON", "DINO", "AFRO", "2340c0e0", "6003943", "6003948", "GRIDDY", "1641872214176")) {
            assertFalse(all.contains(accountData), accountData + " survived: " + all);
        }
        assertTrue(kept.get(0).contains("\"payload\":{\"outfits\":[{\"a\":\"1\",\"b\":\"Outfit #1\",\"c\":\"0;" + Recording.STEVE
                + "\",\"j\":\"1\",\"d\":{},\"e\":{},\"f\":true,\"g\":0,\"i\":0}]}"), kept.get(0));
        // The unlock packet stays (Essential waits for it before "loaded"), with nothing in it.
        assertTrue(kept.get(1).contains("\"payload\":{\"a\":[],\"b\":{},\"d\":false}"), kept.get(1));
        assertTrue(kept.get(2).contains("\"payload\":{\"a\":[{\"a\":\"1\",\"b\":true,\"c\":{},\"d\":0,\"e\":0}]}"), kept.get(2));
    }

    @Test
    void anAccountWithoutOutfitsStaysWithout() {
        String none = recv(Recording.OUTFITS, "{\"outfits\": []}", "");
        assertTrue(Recording.filter(List.of(none)).get(0).contains("\"payload\":{\"outfits\":[]}"));
    }

    @Test
    void skipsLinesThatAreNotJsonObjects() {
        String cosmetics = recv("cosmetic.ServerCosmeticTypesPopulatePacket", "{}", "");
        assertEquals(List.of(cosmetics), Recording.filter(List.of("", "garbage", "[1]", cosmetics)));
    }

    @Test
    void picksTheNewestPlainLog(@TempDir Path dir) throws Exception {
        assertNull(Recording.newestLog(dir.resolve("missing")));
        Path old = Files.write(dir.resolve("2026-01-01.log"), new byte[0]);
        Path newer = Files.write(dir.resolve("2026-01-02.log"), new byte[0]);
        Path gz = Files.write(dir.resolve("2026-01-03.log.gz"), new byte[0]);
        Files.setLastModifiedTime(old, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
        Files.setLastModifiedTime(gz, FileTime.fromMillis(3000));
        assertEquals(newer, Recording.newestLog(dir));
    }

    @Test
    void recordWritesTheFilteredLines(@TempDir Path dir) throws Exception {
        String cosmetics = recv("cosmetic.ServerCosmeticTypesPopulatePacket", "{}", "");
        Path log = Files.write(dir.resolve("a.log"), List.of(cosmetics,
                recv("chat.ServerChatChannelMessagePacket", "{}", "")), StandardCharsets.UTF_8);
        assertEquals(cosmetics + "\n", new String(Recording.record(log), StandardCharsets.UTF_8));
    }

    @Test
    void bundlesTheRepositorysCosmeticsList() throws Exception {
        assertTrue(Recorder.bundledList().size() > 500);
    }
}
