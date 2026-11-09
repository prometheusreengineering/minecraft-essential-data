package studio.dreamys.prometheus.essential.replay;

import ing.prometheusreengineer.jsonproxy.Config;
import ing.prometheusreengineer.jsonproxy.Http;
import ing.prometheusreengineer.jsonproxy.Match;
import ing.prometheusreengineer.jsonproxy.ProxyServer;
import ing.prometheusreengineer.jsonproxy.Rule;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * {@code java -jar cm-replay.jar <recording.jsonl> [--port 47801] [--join-port 47802] [--unanswered <file>]}
 *
 * <p>Serves the recording on {@code ws://127.0.0.1:<port>/} and Mojang's join endpoint on
 * {@code http://127.0.0.1:<join-port>}, both on loopback only, until killed. See the essential repo's verity.json for
 * how the game is pointed at them.
 */
public final class Main {
    private Main() {}

    /**
     * Stands in for Mojang's session server ({@code minecraft.api.session.host}). Before connecting, Essential announces
     * the connection there with the player's access token; Essential's real server then checks that with Mojang, but
     * the replay server doesn't, so the replayed clients need no session. {@code POST /session/minecraft/join} gets 204
     * like Mojang's; everything else gets jsonproxy's offline 503.
     */
    static ProxyServer joinServer(int port) {
        return new ProxyServer(new Config().port(port).offline(true).label("cm-replay-join")
                .rules(List.of(Rule.respond("join", Match.post("/session/minecraft/join"), req -> Http.Res.empty(204)))));
    }

    public static void main(String[] args) throws Exception {
        Path recording = null;
        int port = 47801;
        int joinPort = 47802;
        Path unansweredFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--join-port" -> joinPort = Integer.parseInt(args[++i]);
                case "--unanswered" -> unansweredFile = Path.of(args[++i]);
                default -> recording = Path.of(args[i]);
            }
        }
        if (recording == null) {
            System.err.println("usage: cm-replay <recording.jsonl> [--port N] [--join-port N] [--unanswered FILE]");
            System.exit(2);
        }
        Session session = Session.load(recording);
        InetAddress loopback = InetAddress.getLoopbackAddress();

        Set<String> seen = ConcurrentHashMap.newKeySet();
        Path out = unansweredFile;
        ReplayServer server = new ReplayServer(new InetSocketAddress(loopback, port), session, request -> {
            // One line per distinct request; the payload is cut, since a legacy request lists hundreds of ids.
            String line = request.length() > 300 ? request.substring(0, 300) + "..." : request;
            if (!seen.add(line)) return;
            System.out.println("cm-replay: no recorded answer for " + line);
            if (out != null) append(out, line);
        });
        ProxyServer join = joinServer(joinPort);
        join.start();
        server.start();
        System.out.println("cm-replay: protocol " + session.protocolVersion + ", " + session.pushes.size()
                + " pushes, " + session.cosmeticCount() + " cosmetics, replies for " + session.requestNames()
                + "; ws://127.0.0.1:" + port + "/, Mojang join http://127.0.0.1:" + join.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("cm-replay: served " + server.clients.size() + " connections");
            join.stop();
        }));
        new CountDownLatch(1).await();
    }

    private static synchronized void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("cm-replay: cannot write " + file + ": " + e);
        }
    }
}
