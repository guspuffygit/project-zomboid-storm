package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.connection.StormChunkWire;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.Nullable;
import zombie.core.network.ByteBufferReader;
import zombie.iso.WorldStreamer;
import zombie.network.GameClient;

/**
 * Client side of loading-phase chunk streaming over the Storm game-port TCP channel.
 *
 * <p>Flow: the advice on {@code RequestZipListPacket.write} calls {@link #stageForTcp} — if a TCP
 * session exists and the client is still loading ({@code !GameClient.playerConnectSent}), the
 * requests are snapshotted here and the UDP packet goes out empty. The advice on {@code
 * WorldStreamer.updateMain}'s exit calls {@link #dispatchStaged()} — deliberately <b>after</b>
 * {@code updateMain} published the requests to {@code sentRequests}, because a delivery applied
 * before that publish matches nothing and is silently discarded. A worker thread then fetches the
 * batches over TCP and hands every result to the vanilla {@code WorldStreamer.receiveChunkPart} /
 * {@code receiveNotRequired} methods, which keeps all vanilla bookkeeping (pending-request
 * retirement, cancel sweeps, {@code isBusy()} draining) intact.
 *
 * <p>Those two methods mutate a non-thread-safe list and are vanilla-called from the UdpEngine
 * thread, which can still deliver residual UDP traffic; {@link #RECEIVE_LOCK} serializes both
 * callers (the vanilla entry points are advice-wrapped with the same lock).
 *
 * <p>Fail-soft: any transport error marks the current session broken and stops diverting — the
 * in-flight requests hit vanilla's 8-second timeout, get re-queued with fresh request numbers, and
 * flow over plain UDP.
 */
public final class StormChunksOverTcp {

    /** Serializes vanilla UdpEngine-thread deliveries against the TCP worker's deliveries. */
    public static final ReentrantLock RECEIVE_LOCK = new ReentrantLock();

    /** Vanilla ccr batch size; the server endpoint enforces the same cap. */
    private static final int BATCH_SIZE = 20;

    /** Vanilla {@code MAX_CHUNK_SEND_TRIES}: retries for chunks the server is still loading. */
    private static final int MAX_RETRIES = 3;

    private static final long RETRY_DELAY_MILLIS = 250;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Staged(int requestNumber, int wx, int wy, long crc, int attempts) {}

    /** Client main thread only (staged inside updateMain, drained on its exit). */
    private static final List<Staged> staged = new ArrayList<>();

    private static final LinkedBlockingQueue<List<Staged>> dispatchQueue =
            new LinkedBlockingQueue<>();

    /** Session the transport last failed on; diversion stays off until a new session exists. */
    private static volatile @Nullable StormTcpChannel.Session brokenSession;

    @SuppressWarnings("unused")
    private static @Nullable Thread worker;

    private StormChunksOverTcp() {}

    /**
     * Snapshot the requests for TCP transfer instead of the UDP packet. Returns false — leaving the
     * vanilla packet write to run — unless a healthy TCP session exists and the client is still in
     * the loading phase. Called from the {@code RequestZipListPacket.write} advice on the client
     * main thread; must never throw into the woven method.
     */
    public static boolean stageForTcp(ArrayList<WorldStreamer.ChunkRequest> requests) {
        try {
            StormTcpChannel.Session session = StormTcpChannel.getSession();
            if (session == null
                    || session == brokenSession
                    || GameClient.instance.playerConnectSent) {
                return false;
            }
            for (WorldStreamer.ChunkRequest request : requests) {
                staged.add(
                        new Staged(
                                request.requestNumber,
                                request.chunk.wx,
                                request.chunk.wy,
                                request.crc,
                                0));
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to stage chunk requests for TCP; falling back to UDP", t);
            staged.clear();
            brokenSession = StormTcpChannel.getSession();
            return false;
        }
    }

    /**
     * Hand the staged requests to the worker. Called from the {@code WorldStreamer.updateMain} exit
     * advice — after the requests were published to {@code sentRequests}, so a fast TCP response
     * cannot race the publish. Must never throw into the woven method.
     */
    public static void dispatchStaged() {
        try {
            if (staged.isEmpty()) {
                return;
            }
            ensureWorker();
            dispatchQueue.add(new ArrayList<>(staged));
            staged.clear();
        } catch (Throwable t) {
            LOGGER.error("Failed to dispatch staged chunk requests; falling back to UDP", t);
            staged.clear();
            brokenSession = StormTcpChannel.getSession();
        }
    }

    private static synchronized void ensureWorker() {
        if (worker == null) {
            Thread thread = new Thread(StormChunksOverTcp::run, "storm-chunk-tcp");
            thread.setDaemon(true);
            thread.start();
            worker = thread;
        }
    }

    private static void run() {
        while (true) {
            List<Staged> batch;
            try {
                batch = dispatchQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            StormTcpChannel.Session session = StormTcpChannel.getSession();
            try {
                List<Staged> retries = fetchAll(batch);
                while (!retries.isEmpty()) {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                    retries = fetchAll(retries);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Unfetched requests hit the vanilla 8s timeout and re-issue over UDP.
                brokenSession = session;
                LOGGER.warn(
                        "Chunk transfer over TCP failed; falling back to UDP for this session: {}",
                        t.toString());
            }
        }
    }

    /** Fetch in ccr-sized sub-batches; returns the requests the server asked to retry. */
    private static List<Staged> fetchAll(List<Staged> batch) throws Exception {
        List<Staged> retries = new ArrayList<>();
        for (int from = 0; from < batch.size(); from += BATCH_SIZE) {
            fetchBatch(batch.subList(from, Math.min(from + BATCH_SIZE, batch.size())), retries);
        }
        return retries;
    }

    private static void fetchBatch(List<Staged> batch, List<Staged> retries) throws Exception {
        HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest("/storm/game/chunks");
        if (builder == null) {
            // Session gone (disconnect mid-flight); the requests are moot.
            return;
        }
        HttpResponse<byte[]> response =
                StormTcpChannel.send(
                        builder.header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(toJson(batch)))
                                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("chunk batch returned HTTP " + response.statusCode());
        }
        Map<Integer, Staged> byNumber = new HashMap<>();
        for (Staged request : batch) {
            byNumber.put(request.requestNumber(), request);
        }
        for (StormChunkWire.Entry entry : StormChunkWire.read(response.body())) {
            Staged request = byNumber.get(entry.requestNumber());
            if (request == null) {
                continue;
            }
            switch (entry.kind()) {
                case StormChunkWire.KIND_DATA -> applyData(entry.requestNumber(), entry.data());
                case StormChunkWire.KIND_NOT_REQUIRED ->
                        applyNotRequired(entry.requestNumber(), entry.sameOnServer());
                case StormChunkWire.KIND_RETRY -> {
                    if (request.attempts() + 1 >= MAX_RETRIES) {
                        // Vanilla terminal verdict after 3 tries: sameOnServer = (crc == 0).
                        applyNotRequired(entry.requestNumber(), request.crc() == 0);
                    } else {
                        retries.add(
                                new Staged(
                                        request.requestNumber(),
                                        request.wx(),
                                        request.wy(),
                                        request.crc(),
                                        request.attempts() + 1));
                    }
                }
                default -> throw new IllegalStateException("unknown entry kind " + entry.kind());
            }
        }
    }

    private static String toJson(List<Staged> batch) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (Staged request : batch) {
            ObjectNode entry = requests.addObject();
            entry.put("requestNumber", request.requestNumber());
            entry.put("wx", request.wx());
            entry.put("wy", request.wy());
            entry.put("crc", request.crc());
        }
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Deliver a whole chunk as one synthetic {@code SentChunk} fragment. The vanilla receive path
     * is size-agnostic (fragmentation is a sender concern), so a single fragment covering the full
     * file exercises the exact vanilla bookkeeping: buffer allocation, {@code partsReceived},
     * pending-request retirement and the cancel sweep.
     */
    private static void applyData(int requestNumber, byte[] zip) {
        ByteBuffer buffer = ByteBuffer.allocate(24 + zip.length);
        buffer.putInt(requestNumber);
        buffer.putInt(1); // numChunks
        buffer.putInt(0); // chunkIndex
        buffer.putInt(zip.length); // fileSize
        buffer.putInt(0); // offset
        buffer.putInt(zip.length); // count
        buffer.put(zip);
        buffer.position(0);
        RECEIVE_LOCK.lock();
        try {
            WorldStreamer.instance.receiveChunkPart(new ByteBufferReader(buffer));
        } finally {
            RECEIVE_LOCK.unlock();
        }
    }

    private static void applyNotRequired(int requestNumber, boolean sameOnServer) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.putInt(1); // count
        buffer.putInt(requestNumber);
        buffer.put((byte) (sameOnServer ? 1 : 0));
        buffer.position(0);
        RECEIVE_LOCK.lock();
        try {
            WorldStreamer.instance.receiveNotRequired(new ByteBufferReader(buffer));
        } finally {
            RECEIVE_LOCK.unlock();
        }
    }
}
