package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToLongFunction;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Mass-join performance test for the game-port TCP world-loading path: twenty clients dial the
 * server at one wall-clock instant and every one of them must complete the whole loading-phase
 * download.
 *
 * <p><b>Not part of {@code check}.</b> It boots a dedicated server, spawns twenty child JVMs and
 * runs for minutes. Run it on demand:
 *
 * <pre>
 *   ./gradlew tcpLoadTest
 *   ./gradlew tcpLoadTest -Dstorm.perftest.clients=40 -Dstorm.perftest.budgetSeconds=300
 * </pre>
 *
 * <h2>What it stresses</h2>
 *
 * Moving world loading off UDP concentrated it on two shared, finite resources that vanilla's
 * per-connection UDP pipeline never had:
 *
 * <ul>
 *   <li><b>{@code GamePortHttpServer}'s fixed four-thread handler pool.</b> Every loading endpoint
 *       blocks its pool thread on a {@code StormServerTaskQueue} round trip, so four joiners can
 *       occupy the whole pool and the rest queue behind them on the socket backlog.
 *   <li><b>The once-per-tick {@code StormServerTaskQueue} drain.</b> Chunk serialization, payload
 *       serialization and the profile query all run on the server main thread, one drain per tick.
 *       Twenty joiners issue roughly three hundred of those round trips between them.
 * </ul>
 *
 * Both failure modes are loud rather than slow: the endpoints answer {@code 503 server busy} when
 * the queue does not drain inside fifteen seconds, and {@link StormTcpLoadingClient} throws on any
 * non-200. A starved pool or a stalled tick therefore fails a client outright.
 *
 * <h2>Why the test stamps steam ids</h2>
 *
 * {@code StormTcpSessionRegistry} binds a handshake to a live RakNet connection by (steamId, source
 * IP). The test server runs {@code -nosteam}, where vanilla {@code LoginPacket.processServer} never
 * calls {@code setSteamId}, so every connection keeps steamId 0 — and all twenty children dial from
 * 127.0.0.1. Left alone, all twenty handshakes would bind to the first connection and each new one
 * would evict the previous token. So the test stamps distinct ids through {@code POST /eval},
 * standing in for the Steam authentication a real server performs. The same collision is real for
 * {@code -nosteam} servers whose players share a NAT; it is worth fixing separately, and is not
 * what this test measures.
 *
 * <h2>What "fully loaded" means here</h2>
 *
 * Each client completes the vanilla RakNet login through {@code ConnectedPlayer}, then downloads
 * everything the loading phase pulls over TCP: its saved profiles, all four bulk payloads, and the
 * 13x13 chunk grid a joining client streams around its spawn. That is the whole surface the TCP
 * change owns. Rendering and player spawn are client-side work with no headless harness.
 */
@Tag("perf")
@ExtendWith(ServerExtension.class)
class ConcurrentJoinTcpPerfTest implements IntegrationTest {

    /** Username stem; the numeric suffix is what the steam-id stamping script keys on. */
    private static final String USERNAME_PREFIX = "tcpperf";

    private static final String PASSWORD = "tcpperfpass";

    /** Well outside any real steam id range, so a stamped connection is obvious in logs. */
    private static final long STEAM_ID_BASE = 76561190000000000L;

    private static final String HOST = "127.0.0.1";

    /** Storm's game-port TCP listener binds the game's UDP port number. */
    private static final int GAME_PORT = ServerExtension.TEST_RAKNET_PORT;

    private static final String BACKEND_URL =
            "http://" + HOST + ":" + ServerExtension.TEST_HTTP_PORT;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SPAWN_TIMEOUT = Duration.ofSeconds(120);

    /** Head start so every child is parked in its sleep before the release instant passes. */
    private static final Duration RELEASE_LEAD = Duration.ofSeconds(3);

    private static final int CLIENT_COUNT = Integer.getInteger("storm.perftest.clients", 20);

    /** {@code IsoChunkMap.chunkGridWidth} — the grid a client streams on join. */
    private static final int CHUNK_GRID_WIDTH = Integer.getInteger("storm.perftest.gridWidth", 13);

    private static final long BUDGET_SECONDS = Long.getLong("storm.perftest.budgetSeconds", 180L);

    private static final List<LiveServerClientProcess> clients = new ArrayList<>();

    private static int spawnChunkX;
    private static int spawnChunkY;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void createCharacters() throws Exception {
        for (int i = 0; i < CLIENT_COUNT; i++) {
            String username = username(i);
            String reply =
                    ServerExtension.sendCommandAndAwaitOutput(
                            "stormcreatechar " + username,
                            "for " + username,
                            Duration.ofSeconds(30));
            Assertions.assertNotNull(
                    reply, "stormcreatechar " + username + " produced no server output");
        }
    }

    @AfterAll
    static void closeClients() {
        clients.forEach(LiveServerClientProcess::close);
    }

    @Test
    void twentySimultaneousJoinsAllCompleteTcpWorldLoading() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(
                compiler != null, "No system Java compiler (running on a JRE) — skipping");
        assertGamePortReachable();
        readSpawnChunk(compiler);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients.add(LiveServerClientProcess.spawn(USERNAME_PREFIX + i));
        }
        for (int i = 0; i < clients.size(); i++) {
            Assertions.assertTrue(
                    clients.get(i).awaitReady(SPAWN_TIMEOUT),
                    "client " + i + " never initialized PZ client natives");
        }

        connectAllAtOnce();
        stampDistinctSteamIds(compiler);
        List<StormTcpLoadingClient.Result> results = loadAllAtOnce();

        assertEveryChunkRequestResolved(results);
        Assertions.assertTrue(
                results.stream().mapToLong(StormTcpLoadingClient.Result::requestDataBytes).sum()
                        > 0,
                "every request-data payload came back empty — the endpoint is answering 200 with"
                        + " nothing, so this run measured no bulk transfer at all");
    }

    /** Releases every child's RakNet dial at one instant and waits for all of them to log in. */
    private void connectAllAtOnce() throws Exception {
        Instant release = Instant.now().plus(RELEASE_LEAD);
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i)
                    .sendConnect(
                            HOST,
                            ServerExtension.TEST_RAKNET_PORT,
                            ServerExtension.TEST_SERVER_PASSWORD,
                            username(i),
                            PASSWORD,
                            CONNECT_TIMEOUT,
                            release);
        }
        long started = System.currentTimeMillis();
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).awaitConnected(CONNECT_TIMEOUT.plusSeconds(30));
        }
        System.out.printf(
                "[perf] %d clients completed the RakNet login in %d ms%n",
                clients.size(), System.currentTimeMillis() - started);
    }

    /**
     * Releases every child's TCP loading-phase download at one instant and collects the results.
     */
    private List<StormTcpLoadingClient.Result> loadAllAtOnce() throws Exception {
        Instant release = Instant.now().plus(RELEASE_LEAD);
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i)
                    .sendTcpLoad(
                            HOST,
                            GAME_PORT,
                            STEAM_ID_BASE + i,
                            spawnChunkX,
                            spawnChunkY,
                            CHUNK_GRID_WIDTH,
                            release);
        }
        Duration budget = Duration.ofSeconds(BUDGET_SECONDS);
        List<StormTcpLoadingClient.Result> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            try {
                results.add(clients.get(i).awaitTcpLoad(budget.plusSeconds(60)));
            } catch (Exception e) {
                failures.add(username(i) + ": " + e.getMessage());
            }
        }
        long wallClockMillis = Duration.between(release, Instant.now()).toMillis();
        report(results, wallClockMillis);

        Assertions.assertTrue(
                failures.isEmpty(),
                () ->
                        failures.size()
                                + " of "
                                + clients.size()
                                + " clients failed to finish loading over TCP:"
                                + System.lineSeparator()
                                + String.join(System.lineSeparator(), failures));
        Assertions.assertTrue(
                ServerExtension.getServerProcess().isAlive(),
                "the server process died during the mass join; see "
                        + ServerExtension.getLogFile());
        Assertions.assertTrue(
                wallClockMillis <= budget.toMillis(),
                () ->
                        "mass join took "
                                + wallClockMillis
                                + " ms, over the "
                                + budget.toSeconds()
                                + " s budget (-Dstorm.perftest.budgetSeconds)");
        return results;
    }

    private static void assertEveryChunkRequestResolved(
            List<StormTcpLoadingClient.Result> results) {
        for (int i = 0; i < results.size(); i++) {
            StormTcpLoadingClient.Result result = results.get(i);
            int resolved =
                    result.chunkData() + result.chunkNotRequired() + result.chunkRetryExhausted();
            Assertions.assertEquals(
                    result.chunkRequests(),
                    resolved,
                    username(i)
                            + " left "
                            + (result.chunkRequests() - resolved)
                            + " chunk requests without a verdict — the server answered a batch with"
                            + " fewer entries than it was asked for");
        }
    }

    // ---- Reporting ----

    private static void report(List<StormTcpLoadingClient.Result> results, long wallClockMillis) {
        if (results.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.printf(
                "[perf] %d clients, %dx%d chunk grid at chunk %d,%d — wall clock %d ms%n",
                results.size(),
                CHUNK_GRID_WIDTH,
                CHUNK_GRID_WIDTH,
                spawnChunkX,
                spawnChunkY,
                wallClockMillis);
        System.out.printf(
                "[perf] %-14s %8s %8s %8s %8s%n",
                "phase", "p50 ms", "p95 ms", "max ms", "total ms");
        printPhase(results, "handshake", StormTcpLoadingClient.Result::handshakeMillis);
        printPhase(results, "profiles", StormTcpLoadingClient.Result::profilesMillis);
        printPhase(results, "request-data", StormTcpLoadingClient.Result::requestDataMillis);
        printPhase(results, "chunks", StormTcpLoadingClient.Result::chunksMillis);
        printPhase(results, "per client", StormTcpLoadingClient.Result::totalMillis);

        long requests = sumInt(results, StormTcpLoadingClient.Result::httpRequests);
        long chunkData = sumInt(results, StormTcpLoadingClient.Result::chunkData);
        long notRequired = sumInt(results, StormTcpLoadingClient.Result::chunkNotRequired);
        long retryExhausted = sumInt(results, StormTcpLoadingClient.Result::chunkRetryExhausted);
        long bytes =
                sum(results, StormTcpLoadingClient.Result::requestDataBytes)
                        + sum(results, StormTcpLoadingClient.Result::chunkBytes);
        System.out.printf(
                "[perf] %d HTTP requests, %.1f MiB transferred, %d req/s%n",
                requests,
                bytes / (1024.0 * 1024.0),
                wallClockMillis == 0 ? requests : requests * 1000 / wallClockMillis);
        System.out.printf(
                "[perf] chunk verdicts: %d data, %d not-required, %d retry-exhausted%n",
                chunkData, notRequired, retryExhausted);

        StormTcpLoadingClient.Result slowest =
                results.stream()
                        .max(
                                java.util.Comparator.comparingLong(
                                        StormTcpLoadingClient.Result::slowestRequestMillis))
                        .orElseThrow();
        System.out.printf(
                "[perf] slowest single request: %d ms on %s%n",
                slowest.slowestRequestMillis(), slowest.slowestRequestPath());
        System.out.println();
    }

    private static void printPhase(
            List<StormTcpLoadingClient.Result> results,
            String label,
            ToLongFunction<StormTcpLoadingClient.Result> phase) {
        long[] values = results.stream().mapToLong(phase).sorted().toArray();
        System.out.printf(
                "[perf] %-14s %8d %8d %8d %8d%n",
                label,
                percentile(values, 50),
                percentile(values, 95),
                values[values.length - 1],
                Arrays.stream(values).sum());
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long sum(
            List<StormTcpLoadingClient.Result> results,
            ToLongFunction<StormTcpLoadingClient.Result> field) {
        return results.stream().mapToLong(field).sum();
    }

    private static long sumInt(
            List<StormTcpLoadingClient.Result> results,
            java.util.function.ToIntFunction<StormTcpLoadingClient.Result> field) {
        return results.stream().mapToInt(field).sum();
    }

    // ---- Server-side fixtures ----

    private void assertGamePortReachable() throws Exception {
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://" + HOST + ":" + GAME_PORT + "/storm/ping"))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(
                200,
                response.statusCode(),
                "the game-port TCP server is not answering on port "
                        + GAME_PORT
                        + "; without it there is nothing to load over, see "
                        + ServerExtension.getStormMainLogFile());
    }

    /** Reads the world's first spawn point and converts it to the chunk coordinate clients load. */
    private void readSpawnChunk(JavaCompiler compiler) throws Exception {
        String reply =
                eval(
                        compiler,
                        """
                        zombie.iso.SpawnPoints points = zombie.iso.SpawnPoints.instance;
                        if (points != null && !points.getSpawnPoints().isEmpty()) {
                            zombie.characters.IsoGameCharacter.Location spawn =
                                    points.getSpawnPoints().get(0);
                            return spawn.x + "," + spawn.y;
                        }
                        return "10745,9412";
                        """);
        String[] parts = reply.trim().split(",");
        Assertions.assertEquals(2, parts.length, "unexpected spawn-point reply: " + reply);
        spawnChunkX = (int) Float.parseFloat(parts[0]) / 8;
        spawnChunkY = (int) Float.parseFloat(parts[1]) / 8;
    }

    /**
     * Gives each connection the distinct steam id a Steam-authenticated server would have stamped
     * in {@code LoginPacket.processServer}, so the twenty handshakes bind to twenty connections
     * instead of colliding on steamId 0. See the class javadoc.
     */
    private void stampDistinctSteamIds(JavaCompiler compiler) throws Exception {
        String reply =
                eval(
                        compiler,
                        """
                        java.util.List<zombie.core.raknet.UdpConnection> connections =
                                zombie.network.GameServer.udpEngine.connections;
                        int stamped = 0;
                        for (int i = 0; i < connections.size(); i++) {
                            zombie.core.raknet.UdpConnection connection = connections.get(i);
                            if (connection == null) {
                                continue;
                            }
                            String name = connection.getUserName();
                            if (name == null || !name.startsWith("%s")) {
                                continue;
                            }
                            try {
                                connection.setSteamId(
                                        %dL + Long.parseLong(name.substring(%d)));
                                stamped++;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        return "stamped=" + stamped;
                        """
                                .formatted(
                                        USERNAME_PREFIX, STEAM_ID_BASE, USERNAME_PREFIX.length()));
        Assertions.assertEquals(
                "stamped=" + clients.size(),
                reply.trim(),
                "expected every connected test client to get a steam id, got: " + reply);
    }

    /** Compiles {@code body} into an {@code EvalScript.run()} and runs it on the server JVM. */
    private String eval(JavaCompiler compiler, String body) throws Exception {
        Path sourceDir = Files.createTempDirectory("storm-perftest-eval-src");
        Path classesDir = Files.createTempDirectory("storm-perftest-eval-classes");
        Path sourceFile = sourceDir.resolve("EvalScript.java");
        Files.writeString(
                sourceFile,
                "public class EvalScript {\n"
                        + "    public static Object run() throws Exception {\n"
                        + body
                        + "\n    }\n}\n");
        int rc =
                compiler.run(
                        null,
                        null,
                        null,
                        "-cp",
                        System.getProperty("java.class.path"),
                        "-d",
                        classesDir.toString(),
                        sourceFile.toString());
        Assertions.assertEquals(0, rc, "EvalScript.java failed to compile");

        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(BACKEND_URL + "/eval"))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/java-vm")
                                .POST(
                                        HttpRequest.BodyPublishers.ofByteArray(
                                                Files.readAllBytes(
                                                        classesDir.resolve("EvalScript.class"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), () -> "eval body: " + response.body());
        Assertions.assertFalse(
                response.body().startsWith("ERROR:"), "eval failed: " + response.body());
        return response.body();
    }

    private static String username(int index) {
        return USERNAME_PREFIX + index;
    }
}
