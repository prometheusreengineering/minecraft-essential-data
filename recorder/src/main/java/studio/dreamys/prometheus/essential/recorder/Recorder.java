package studio.dreamys.prometheus.essential.recorder;

import gg.essential.data.OnboardingData;
import gg.essential.lib.gson.Gson;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.connectionmanager.cosmetics.InfraCosmeticsData;
import gg.essential.network.cosmetics.Cosmetic;
import gg.essential.util.MagicPathsKt;
import ing.prometheusreengineer.verity.api.Verity;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Signs plain Essential in (Verity provides the account), asks it for every cosmetic in the bundled
 * {@code cosmetics.json} the way the Prometheus patch does, waits until the server has answered, then attaches
 * Essential's own packet log, filtered and neutralized by {@link Recording}, as {@value Recording#FILE}.
 *
 * <p>Runs on the main thread from {@code CosmeticsManager#tick}, where Essential's state lives. Does nothing unless
 * Verity launched the game.
 */
public final class Recorder {
    private enum Phase { START, CONNECT, LOAD, SETTLE, DONE }

    /** Mojang's sign-in and Essential's first connect can take a while on a cold runner. */
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("recorder.connectTimeout", 600));
    private static final long PHASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("recorder.timeout", 240));
    /** The server keeps sending cosmetics (the requested ones) for a while after "loaded". */
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("recorder.settle", 15));
    /** Essential writes its packet log asynchronously. */
    private static final int FLUSH_SECONDS = Integer.getInteger("recorder.flush", 10);
    /** The share of the requested ids the server must still know; it retires a few cosmetics for good now and then. */
    private static final double REQUESTED_RATIO = Double.parseDouble(System.getProperty("recorder.requestedRatio", "0.9"));

    private static Phase phase = Phase.START;
    private static long phaseStart;
    private static int lastCount = -1;
    private static long lastChange;
    private static Set<String> requested;

    private Recorder() {}

    public static void tick(CosmeticsManager cosmetics, ConnectionManager connection, InfraCosmeticsData infra) {
        if (phase == Phase.DONE || !Verity.isActive()) return;
        try {
            step(cosmetics, connection, infra);
        } catch (Throwable t) {
            Verity.fail("recorder", t);
            done(false);
        }
    }

    private static void step(CosmeticsManager cosmetics, ConnectionManager connection, InfraCosmeticsData infra) throws Exception {
        long now = System.nanoTime();
        long elapsed = now - phaseStart;
        switch (phase) {
            case START -> {
                // Essential's connect loop waits for its Terms of Service, which nobody can accept in a fresh headless
                // instance; Verity sandboxes the game and home folders, so this only answers for the test instance.
                Verity.test("Essential's Terms of Service accepted", () -> {
                    if (!OnboardingData.hasAcceptedTos()) OnboardingData.setAcceptedTos();
                    if (!OnboardingData.hasAcceptedTos()) throw new AssertionError("still not accepted");
                });
                requested = bundledList();
                next(Phase.CONNECT, now);
            }
            case CONNECT -> {
                if (connection.isOpen()) {
                    Verity.pass("connected to Essential's servers after " + seconds(elapsed));
                    // Through InfraCosmeticsData, like the patch: they count as active requests, so "loaded" waits.
                    infra.requestCosmeticsIfMissing(requested);
                    next(Phase.LOAD, now);
                } else if (elapsed > CONNECT_TIMEOUT_NANOS) {
                    Verity.fail("connected to Essential's servers", "not connected after " + seconds(elapsed)
                            + ". Is the account signed in (VERITY_MSA_REFRESH_TOKEN), and are Essential's servers up?");
                    done(false);
                }
            }
            case LOAD -> {
                if (Boolean.TRUE.equals(stateValue(cosmetics, "getCosmeticsLoaded"))) {
                    Verity.pass("Essential loaded its cosmetics after " + seconds(elapsed));
                    next(Phase.SETTLE, now);
                    lastChange = now;
                } else if (elapsed > PHASE_TIMEOUT_NANOS) {
                    Verity.fail("Essential loaded its cosmetics", "still loading after " + seconds(elapsed));
                    done(false);
                }
            }
            case SETTLE -> {
                int count = loadedIds(cosmetics).size();
                if (count != lastCount) {
                    lastCount = count;
                    lastChange = now;
                }
                if (now - lastChange > SETTLE_NANOS || elapsed > PHASE_TIMEOUT_NANOS) {
                    evaluate(cosmetics);
                    done(true);
                }
            }
            default -> {}
        }
    }

    private static void evaluate(CosmeticsManager cosmetics) throws Exception {
        Set<String> loaded = loadedIds(cosmetics);
        Set<String> missing = new TreeSet<>(requested);
        missing.removeAll(loaded);
        int present = requested.size() - missing.size();
        Verity.log("Essential sent " + loaded.size() + " cosmetics, " + present + " of the " + requested.size() + " requested");
        Verity.test("the server answered the cosmetics request", () -> {
            if (present < requested.size() * REQUESTED_RATIO) {
                throw new AssertionError("only " + present + " of the " + requested.size() + " requested cosmetics arrived; missing: " + cap(missing));
            }
        });
    }

    private static void next(Phase p, long now) {
        phase = p;
        phaseStart = now;
    }

    private static void done(boolean record) {
        phase = Phase.DONE;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(FLUSH_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (record) record();
            Verity.finish();
        }, "prometheus-recorder");
        t.setDaemon(true);
        t.start();
    }

    private static void record() {
        Verity.test("session recorded", () -> {
            try {
                Path log = Recording.newestLog(MagicPathsKt.getGlobalEssentialDirectory().resolve("infra-logs"));
                if (log == null) throw new AssertionError("Essential wrote no infra-logs/*.log");
                byte[] recording = Recording.record(log);
                Verity.attach(Recording.FILE, recording);
                Verity.log("Recorded " + recording.length + " bytes of Essential's session from " + log.getFileName());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // Essential's State types live in elementa, a library nested in its jar and not on our compile classpath.
    private static Object stateValue(CosmeticsManager manager, String getter) throws Exception {
        Object state = CosmeticsManager.class.getMethod(getter).invoke(manager);
        Class<?> stateClass = Class.forName("gg.essential.gui.elementa.state.v2.State", false, CosmeticsManager.class.getClassLoader());
        return stateClass.getMethod("get").invoke(state);
    }

    private static Set<String> loadedIds(CosmeticsManager manager) throws Exception {
        Set<String> ids = new HashSet<>();
        for (Object o : (Iterable<?>) stateValue(manager, "getCosmetics")) ids.add(((Cosmetic) o).getId());
        return ids;
    }

    static Set<String> bundledList() throws IOException {
        InputStream in = Recorder.class.getClassLoader().getResourceAsStream("cosmetics.json");
        if (in == null) throw new IOException("bundled cosmetics.json not found");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new LinkedHashSet<>(Arrays.asList(new Gson().fromJson(reader, String[].class)));
        }
    }

    private static String cap(Set<String> ids) {
        return ids.size() <= 30 ? ids.toString() : ids.stream().limit(30).toList() + " and " + (ids.size() - 30) + " more";
    }

    private static String seconds(long nanos) {
        return TimeUnit.NANOSECONDS.toSeconds(nanos) + "s";
    }
}
