package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormChunkWire;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import zombie.ChunkMapFilenames;
import zombie.iso.IsoChunk;
import zombie.network.ChunkChecksum;
import zombie.network.ServerMap;

/**
 * Serves world-chunk downloads over the game-port TCP channel for the loading phase, replacing
 * vanilla's {@code RequestZipList} → {@code SentChunk} UDP pipeline (1000-byte fragments, one
 * 20-chunk batch per server tick per connection).
 *
 * <p>The split of work mirrors vanilla's {@code PlayerDownloadServer} exactly: chunks that are
 * loaded in {@code ServerMap} are serialized <b>on the server main thread</b> (via {@link
 * StormServerTaskQueue}, matching {@code PlayerDownloadServer.update()}), while disk reads ({@code
 * IsoChunk.SafeRead}, per-coordinate read lock), CRC checks and zlib compression run on the HTTP
 * pool thread (matching the vanilla worker thread). Batches are capped at vanilla's 20-chunk ccr
 * size so the main-thread cost per request equals vanilla's per-tick cost.
 *
 * <p>A chunk that is neither loaded nor on disk gets a {@code RETRY} verdict — vanilla re-queues
 * such chunks for up to 3 attempts across ticks because the cell may be mid-load; here the client
 * owns the retry loop.
 */
public class GamePortChunkEndpoints {

    private static final long SERIALIZE_TIMEOUT_SECONDS = 15;
    private static final int MAX_BATCH_SIZE = 20;

    public record ChunkBatchBody(List<Entry> requests) {
        public record Entry(int requestNumber, int wx, int wy, long crc) {}
    }

    private record Serialized(byte[][] raw, boolean[] failed) {}

    @GameHttpEndpoint(path = "/storm/game/chunks", method = "POST")
    public static void chunks(HttpRequestEvent event, ChunkBatchBody body) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        if (StormTcpSessionRegistry.liveConnection(session) == null) {
            event.send(403, "game connection gone");
            return;
        }
        List<ChunkBatchBody.Entry> requests = body.requests();
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            event.send(400, "batch must contain 1.." + MAX_BATCH_SIZE + " requests");
            return;
        }
        try {
            Serialized serialized =
                    StormServerTaskQueue.submit(() -> serializeLoaded(requests))
                            .get(SERIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<StormChunkWire.Entry> entries = new ArrayList<>(requests.size());
            for (int i = 0; i < requests.size(); i++) {
                entries.add(resolve(requests.get(i), serialized.raw()[i], serialized.failed()[i]));
            }
            event.setContentType("application/octet-stream");
            event.send(200, StormChunkWire.write(entries));
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (Exception e) {
            LOGGER.error("Failed to serve chunk batch over TCP", e);
            event.send(500, "chunk batch failed");
        }
    }

    /**
     * Mirrors the in-memory branch of {@code PlayerDownloadServer.update()}: serialize every
     * requested chunk that is loaded in {@code ServerMap}. Main thread only; {@code raw[i]} stays
     * null for not-loaded chunks (resolved from disk on the HTTP thread), {@code failed[i]} marks a
     * serialization error (vanilla answers it with {@code NotRequiredInZip(sameOnServer=false)}).
     */
    private static Serialized serializeLoaded(List<ChunkBatchBody.Entry> requests) {
        byte[][] raw = new byte[requests.size()][];
        boolean[] failed = new boolean[requests.size()];
        ByteBuffer bb = ByteBuffer.allocate(16384);
        CRC32 crc32 = new CRC32();
        for (int i = 0; i < requests.size(); i++) {
            ChunkBatchBody.Entry request = requests.get(i);
            IsoChunk chunk = ServerMap.instance.getChunk(request.wx(), request.wy());
            if (chunk != null && chunk.loaded) {
                try {
                    bb = chunk.Save(bb, crc32, false);
                    raw[i] = Arrays.copyOf(bb.array(), bb.position());
                } catch (Exception e) {
                    LOGGER.error(
                            "Failed to serialize chunk {},{} for TCP transfer",
                            request.wx(),
                            request.wy(),
                            e);
                    failed[i] = true;
                }
            }
        }
        return new Serialized(raw, failed);
    }

    /** Disk fallback + CRC shortcut + compression; mirrors {@code WorkerThread.sendArray}. */
    private static StormChunkWire.Entry resolve(
            ChunkBatchBody.Entry request, byte[] raw, boolean failed) throws IOException {
        if (failed) {
            return StormChunkWire.Entry.notRequired(request.requestNumber(), false);
        }
        if (raw == null) {
            if (request.crc() != 0
                    && ChunkChecksum.getChecksum(request.wx(), request.wy()) == request.crc()) {
                return StormChunkWire.Entry.notRequired(request.requestNumber(), true);
            }
            File file = ChunkMapFilenames.instance.getFilename(request.wx(), request.wy());
            if (file == null || !file.exists()) {
                return StormChunkWire.Entry.retry(request.requestNumber());
            }
            ByteBuffer bb = IsoChunk.SafeRead(request.wx(), request.wy(), null);
            raw = Arrays.copyOf(bb.array(), bb.limit());
        }
        if (request.crc() != 0) {
            CRC32 crc = new CRC32();
            crc.update(raw, 0, raw.length);
            if (crc.getValue() == request.crc()) {
                return StormChunkWire.Entry.notRequired(request.requestNumber(), true);
            }
        }
        return StormChunkWire.Entry.data(request.requestNumber(), deflate(raw));
    }

    /** zlib, default level — what the client's shared {@code Inflater} expects. */
    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
