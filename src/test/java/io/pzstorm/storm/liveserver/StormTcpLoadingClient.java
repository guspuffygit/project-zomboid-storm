package io.pzstorm.storm.liveserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.client.StormTcpChannel;
import io.pzstorm.storm.connection.StormChunkWire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only replica of the loading-phase traffic a Storm client puts on the game-port TCP channel,
 * driven without a game window. It reproduces what {@code StormTcpChannel}, {@code
 * StormPlayerProfilesOverTcp}, {@code StormRequestDataOverTcp} and {@code StormChunksOverTcp} send
 * for one join, in the same order and with the same batching and retry rules:
 *
 * <ol>
 *   <li>{@code POST /storm/handshake} — claim the RakNet connection, obtain the session token
 *   <li>{@code GET /storm/game/player-profiles} — the saved network characters
 *   <li>{@code GET /storm/game/request-data?id=…} — the four bulk payloads, in vanilla chain order
 *   <li>{@code POST /storm/game/chunks} — the client's {@code IsoChunkMap.chunkGridWidth}-wide
 *       initial chunk grid, in 20-chunk batches with vanilla's 3-attempt retry rule
 * </ol>
 *
 * <p>Every non-200 response throws. That is the point: the endpoints answer {@code 503 server busy}
 * when the main-thread task queue does not drain inside 15 s and {@code 401} when a session is
 * evicted, so a starved handler pool or a stalled tick surfaces as a failed client rather than a
 * slow one.
 *
 * <p>Requests are issued one at a time per client, mirroring the real client's single {@code
 * storm-chunk-tcp} worker thread — concurrency in the test comes from running many of these at
 * once, not from pipelining inside one.
 */
public final class StormTcpLoadingClient {

    /** Vanilla request-chain order from {@code RequestDataPacket.sendNextRequest}. */
    private static final String[] REQUEST_DATA_CHAIN = {
        "ZombieOutfitDescriptors", "PlayerZombieDescriptors", "RadioData", "WorldMap"
    };

    /** Vanilla ccr batch size; {@code GamePortChunkEndpoints} rejects anything larger. */
    private static final int BATCH_SIZE = 20;

    /** Vanilla {@code MAX_CHUNK_SEND_TRIES}, mirrored by {@code StormChunksOverTcp}. */
    private static final int MAX_RETRIES = 3;

    private static final long RETRY_DELAY_MILLIS = 250;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One client's loading-phase timings and verdict tallies, serialized back to the test JVM. */
    public record Result(
            long handshakeMillis,
            long profilesMillis,
            int profileCount,
            long requestDataMillis,
            long requestDataBytes,
            long chunksMillis,
            int chunkRequests,
            int chunkData,
            int chunkNotRequired,
            int chunkRetryExhausted,
            long chunkBytes,
            int httpRequests,
            long slowestRequestMillis,
            String slowestRequestPath) {

        public long totalMillis() {
            return handshakeMillis + profilesMillis + requestDataMillis + chunksMillis;
        }

        public String toJson() throws Exception {
            return MAPPER.writeValueAsString(this);
        }

        public static Result fromJson(String json) throws Exception {
            return MAPPER.readValue(json, Result.class);
        }
    }

    private record Staged(int requestNumber, int wx, int wy, int attempts) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final String baseUrl;
    private final long steamId;

    private String sessionToken;
    private int httpRequests;
    private long slowestRequestMillis;
    private String slowestRequestPath = "";

    public StormTcpLoadingClient(String host, int gamePort, long steamId) {
        this.baseUrl = "http://" + host + ":" + gamePort;
        this.steamId = steamId;
    }

    /**
     * Runs one client's whole loading-phase download. {@code centerWx}/{@code centerWy} are chunk
     * coordinates (tile / 8) of the spawn point; {@code gridWidth} is the client's {@code
     * IsoChunkMap.chunkGridWidth}.
     */
    public Result run(int centerWx, int centerWy, int gridWidth) throws Exception {
        long handshakeMillis = timed(this::handshake);
        long profilesMillis;
        int profileCount;
        long started = System.nanoTime();
        profileCount = fetchPlayerProfiles();
        profilesMillis = millisSince(started);

        started = System.nanoTime();
        long requestDataBytes = 0;
        for (String id : REQUEST_DATA_CHAIN) {
            requestDataBytes += fetchRequestData(id);
        }
        long requestDataMillis = millisSince(started);

        started = System.nanoTime();
        ChunkTally tally = fetchChunkGrid(centerWx, centerWy, gridWidth);
        long chunksMillis = millisSince(started);

        return new Result(
                handshakeMillis,
                profilesMillis,
                profileCount,
                requestDataMillis,
                requestDataBytes,
                chunksMillis,
                tally.requests,
                tally.data,
                tally.notRequired,
                tally.retryExhausted,
                tally.bytes,
                httpRequests,
                slowestRequestMillis,
                slowestRequestPath);
    }

    private void handshake() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("steamId", Long.toString(steamId));
        body.put("stormVersion", "perf-test");
        HttpResponse<byte[]> response =
                send(
                        "/storm/handshake",
                        HttpRequest.newBuilder()
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                MAPPER.writeValueAsString(body))));
        JsonNode json = MAPPER.readTree(response.body());
        sessionToken = json.get("sessionToken").asText();
    }

    private int fetchPlayerProfiles() throws Exception {
        HttpResponse<byte[]> response =
                send("/storm/game/player-profiles", HttpRequest.newBuilder().GET());
        return MAPPER.readTree(response.body()).path("profiles").size();
    }

    private long fetchRequestData(String id) throws Exception {
        HttpResponse<byte[]> response =
                send("/storm/game/request-data?id=" + id, HttpRequest.newBuilder().GET());
        return response.body().length;
    }

    private static final class ChunkTally {
        int requests;
        int data;
        int notRequired;
        int retryExhausted;
        long bytes;
    }

    /**
     * Requests the square chunk grid the client loads on join, in vanilla-sized batches. A {@code
     * RETRY} verdict means the server has the cell neither in {@code ServerMap} nor on disk; the
     * real client re-asks up to 3 times before treating it as not-required, and so does this.
     */
    private ChunkTally fetchChunkGrid(int centerWx, int centerWy, int gridWidth) throws Exception {
        int half = gridWidth / 2;
        List<Staged> pending = new ArrayList<>();
        int requestNumber = 0;
        for (int wx = centerWx - half; wx <= centerWx + half; wx++) {
            for (int wy = centerWy - half; wy <= centerWy + half; wy++) {
                pending.add(new Staged(requestNumber++, wx, wy, 0));
            }
        }
        ChunkTally tally = new ChunkTally();
        tally.requests = pending.size();
        while (!pending.isEmpty()) {
            List<Staged> retries = new ArrayList<>();
            for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
                fetchChunkBatch(
                        pending.subList(from, Math.min(from + BATCH_SIZE, pending.size())),
                        retries,
                        tally);
            }
            pending = retries;
            if (!pending.isEmpty()) {
                Thread.sleep(RETRY_DELAY_MILLIS);
            }
        }
        return tally;
    }

    private void fetchChunkBatch(List<Staged> batch, List<Staged> retries, ChunkTally tally)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (Staged staged : batch) {
            ObjectNode entry = requests.addObject();
            entry.put("requestNumber", staged.requestNumber());
            entry.put("wx", staged.wx());
            entry.put("wy", staged.wy());
            // A fresh client holds no local copy, so it can never claim the CRC shortcut.
            entry.put("crc", 0);
        }
        HttpResponse<byte[]> response =
                send(
                        "/storm/game/chunks",
                        HttpRequest.newBuilder()
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                MAPPER.writeValueAsString(root))));
        for (StormChunkWire.Entry entry : StormChunkWire.read(response.body())) {
            Staged staged = byRequestNumber(batch, entry.requestNumber());
            if (staged == null) {
                throw new IllegalStateException(
                        "server answered request number "
                                + entry.requestNumber()
                                + " that was not in the batch");
            }
            switch (entry.kind()) {
                case StormChunkWire.KIND_DATA -> {
                    tally.data++;
                    tally.bytes += entry.data() == null ? 0 : entry.data().length;
                }
                case StormChunkWire.KIND_NOT_REQUIRED -> tally.notRequired++;
                case StormChunkWire.KIND_RETRY -> {
                    if (staged.attempts() + 1 >= MAX_RETRIES) {
                        tally.retryExhausted++;
                    } else {
                        retries.add(
                                new Staged(
                                        staged.requestNumber(),
                                        staged.wx(),
                                        staged.wy(),
                                        staged.attempts() + 1));
                    }
                }
                default -> throw new IllegalStateException("unknown entry kind " + entry.kind());
            }
        }
    }

    private static Staged byRequestNumber(List<Staged> batch, int requestNumber) {
        for (Staged staged : batch) {
            if (staged.requestNumber() == requestNumber) {
                return staged;
            }
        }
        return null;
    }

    private HttpResponse<byte[]> send(String path, HttpRequest.Builder builder) throws Exception {
        builder.uri(URI.create(baseUrl + path)).timeout(REQUEST_TIMEOUT);
        if (sessionToken != null) {
            builder.header(StormTcpChannel.SESSION_HEADER, sessionToken);
        }
        long started = System.nanoTime();
        HttpResponse<byte[]> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = millisSince(started);
        httpRequests++;
        if (elapsed > slowestRequestMillis) {
            slowestRequestMillis = elapsed;
            slowestRequestPath = path;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "HTTP "
                            + response.statusCode()
                            + " from "
                            + path
                            + " after "
                            + elapsed
                            + "ms: "
                            + new String(response.body()));
        }
        return response;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static long timed(Step step) throws Exception {
        long started = System.nanoTime();
        step.run();
        return millisSince(started);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
