package io.pzstorm.storm.screenshot;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.lua.StormKahluaTable;
import io.pzstorm.storm.screenshot.commands.ScreenshotChunkCommand;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;

/**
 * Server-side handler for screenshot chunks streamed from the client. Chunks are dropped into a
 * {@link ConcurrentHashMap} on the main thread (cheap reference store, no decode work), and the
 * final concat + base64 decode + file write run on a background executor.
 *
 * <p><b>Every field of a {@code stormScreenshot/chunk} command is attacker-controlled</b> — any
 * connected player can send one at any time, whatever the screenshot feature is configured to do.
 * Three bounds keep a malicious or buggy client from turning that into unbounded server memory:
 *
 * <ul>
 *   <li>{@link #MAX_CHUNKS} caps the declared chunk count <em>before</em> the backing array is
 *       allocated. {@code total} arrives as a Lua number and reaches us through {@code
 *       Double.intValue()}, which saturates at {@link Integer#MAX_VALUE} — so an unchecked {@code
 *       new String[total]} is a request for a ~17 GB array from a single packet.
 *   <li>{@link #MAX_PENDING_PER_PLAYER} caps concurrent in-flight uploads per player, so a client
 *       cannot strand unbounded partial uploads by never finishing them.
 *   <li>{@link #PENDING_TTL_NANOS} evicts a partial upload that has gone quiet. Entries are
 *       otherwise removed only when every chunk arrives, so before this an interrupted upload
 *       (disconnect, crash, packet loss) stranded its base64 chunk strings for the life of the JVM.
 * </ul>
 */
public class StormScreenshotReceiver {

    static final String DISCORD_WEBHOOK_PROPERTY = "storm.screenshot.discord.webhook";

    /**
     * Hard ceiling on the chunk count a client may declare for one screenshot.
     *
     * <p>Worst-case legitimate framing is one {@link StormScreenshotConfig#PIECES_MIN piece} per
     * packet at 24573 source bytes per piece, so 8192 chunks covers a ~201 MB source image — orders
     * of magnitude above any real screenshot. The array for a maxed-out (still rejected-above)
     * request costs 8192 refs = 64 KB, which is the point: the cap is the difference between a
     * bounded allocation and an attacker-chosen one.
     */
    static final int MAX_CHUNKS = 8192;

    /** Concurrent in-flight uploads allowed per player before new ones are refused. */
    static final int MAX_PENDING_PER_PLAYER = 4;

    /** A partial upload is evicted once this long has passed since its last accepted chunk. */
    static final long PENDING_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "StormScreenshotProcessor");
                        t.setDaemon(true);
                        return t;
                    });

    /** Package-private so the bounds tests can seed and inspect it directly. */
    static final ConcurrentHashMap<String, PendingScreenshot> PENDING = new ConcurrentHashMap<>();

    static final class PendingScreenshot {
        final String playerName;
        final short playerOnlineId;
        final String id;
        final int total;
        final String[] chunks;
        final AtomicInteger received = new AtomicInteger(0);

        /**
         * {@link System#nanoTime()} of the last accepted chunk, for {@link #PENDING_TTL_NANOS}
         * eviction. Monotonic, so it is immune to wall-clock steps. Written under the same {@code
         * synchronized (pending)} block that stores a chunk and read unsynchronized by the sweep,
         * hence volatile.
         */
        volatile long lastChunkNanos;

        PendingScreenshot(
                String playerName, short playerOnlineId, String id, int total, long nowNanos) {
            this.playerName = playerName;
            this.playerOnlineId = playerOnlineId;
            this.id = id;
            this.total = total;
            this.chunks = new String[total];
            this.lastChunkNanos = nowNanos;
        }
    }

    @OnClientCommand
    public static void onChunk(ScreenshotChunkCommand event) {
        IsoPlayer player = event.getPlayer();
        if (player == null || player.getUsername() == null) {
            return;
        }

        String id = event.getId();
        Integer indexBox = event.getIndex();
        Integer totalBox = event.getTotal();
        Optional<StormKahluaTable> piecesOpt = event.getPieces();

        if (id == null || indexBox == null || totalBox == null || piecesOpt.isEmpty()) {
            LOGGER.warn("Invalid screenshot chunk from {}", player.getUsername());
            return;
        }

        int index = indexBox;
        int total = totalBox;
        if (!isValidFraming(index, total)) {
            LOGGER.warn(
                    "Invalid screenshot chunk index={} total={} from {} (max total {})",
                    index,
                    total,
                    player.getUsername(),
                    MAX_CHUNKS);
            return;
        }

        StormKahluaTable pieces = piecesOpt.get();
        StringBuilder packetBase64 = new StringBuilder();
        int pieceCount = 0;
        while (true) {
            Object piece = pieces.rawget(pieceCount + 1);
            if (!(piece instanceof String)) {
                break;
            }
            packetBase64.append((String) piece);
            pieceCount++;
        }
        if (pieceCount == 0) {
            LOGGER.warn(
                    "Empty screenshot chunk index={} total={} from {}",
                    index,
                    total,
                    player.getUsername());
            return;
        }
        String data = packetBase64.toString();

        String playerName = player.getUsername();
        short playerOnlineId = player.getOnlineID();
        String key = playerName + "_" + id;

        long now = System.nanoTime();
        PendingScreenshot pending = openOrGet(key, playerName, playerOnlineId, id, total, now);
        if (pending == null) {
            return;
        }

        if (pending.total != total) {
            LOGGER.warn(
                    "Screenshot chunk total mismatch for {} ({} vs {})", key, pending.total, total);
            return;
        }

        int idx = index - 1;
        boolean stored;
        synchronized (pending) {
            if (pending.chunks[idx] != null) {
                stored = false;
            } else {
                pending.chunks[idx] = data;
                pending.lastChunkNanos = now;
                stored = true;
            }
        }
        if (!stored) {
            return;
        }

        int got = pending.received.incrementAndGet();
        LOGGER.debug(
                "Screenshot chunk {}/{} ({} pieces) from {}", index, total, pieceCount, playerName);

        if (got >= total) {
            PENDING.remove(key, pending);
            EXECUTOR.submit(() -> processCompleted(pending));
        }
    }

    /**
     * Framing validation for one {@code stormScreenshot/chunk} command. {@code total} bounds the
     * array {@link PendingScreenshot} is about to allocate, so it must be checked against {@link
     * #MAX_CHUNKS} <em>before</em> any allocation happens.
     */
    static boolean isValidFraming(int index, int total) {
        return total > 0 && total <= MAX_CHUNKS && index > 0 && index <= total;
    }

    /**
     * Returns the pending upload for {@code key}, starting a new one if this is its first chunk, or
     * {@code null} if the player already has {@link #MAX_PENDING_PER_PLAYER} uploads in flight.
     *
     * <p>The sweep and the per-player count run only when a new upload is starting, so the
     * steady-state path for an in-progress upload stays a single map lookup.
     */
    private static PendingScreenshot openOrGet(
            String key,
            String playerName,
            short playerOnlineId,
            String id,
            int total,
            long nowNanos) {
        PendingScreenshot existing = PENDING.get(key);
        if (existing != null) {
            return existing;
        }

        sweepExpired(nowNanos);
        int inFlight = countPendingFor(playerName);
        if (inFlight >= MAX_PENDING_PER_PLAYER) {
            LOGGER.warn(
                    "Refusing new screenshot upload {} from {}: {} already in flight (max {})",
                    id,
                    playerName,
                    inFlight,
                    MAX_PENDING_PER_PLAYER);
            return null;
        }

        PendingScreenshot created =
                new PendingScreenshot(playerName, playerOnlineId, id, total, nowNanos);
        PendingScreenshot raced = PENDING.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    /**
     * Evicts partial uploads whose last accepted chunk is older than {@link #PENDING_TTL_NANOS}.
     *
     * @return how many were dropped
     */
    static int sweepExpired(long nowNanos) {
        int dropped = 0;
        for (Iterator<Map.Entry<String, PendingScreenshot>> it = PENDING.entrySet().iterator();
                it.hasNext(); ) {
            PendingScreenshot p = it.next().getValue();
            long idleNanos = nowNanos - p.lastChunkNanos;
            if (idleNanos < PENDING_TTL_NANOS) {
                continue;
            }
            it.remove();
            dropped++;
            LOGGER.warn(
                    "Dropping stale screenshot upload {} from {} after {}s idle ({}/{} chunks"
                            + " received)",
                    p.id,
                    p.playerName,
                    TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                    p.received.get(),
                    p.total);
        }
        return dropped;
    }

    /** Counts uploads currently in flight for {@code playerName}. */
    static int countPendingFor(String playerName) {
        int count = 0;
        for (PendingScreenshot p : PENDING.values()) {
            if (p.playerName.equals(playerName)) {
                count++;
            }
        }
        return count;
    }

    private static void processCompleted(PendingScreenshot p) {
        try {
            int totalLen = 0;
            for (String chunk : p.chunks) {
                totalLen += chunk.length();
            }
            StringBuilder sb = new StringBuilder(totalLen);
            for (String chunk : p.chunks) {
                sb.append(chunk);
            }
            String base64 = sb.toString();

            byte[] bytes = Base64.getDecoder().decode(base64);
            String filename = "storm_screenshot_" + p.playerName + "_" + p.id + ".png";
            File outFile = new File(LuaManager.getLuaCacheDir(), filename);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                out.write(bytes);
            }
            LOGGER.info(
                    "Saved screenshot from {} ({} bytes) -> {}",
                    p.playerName,
                    bytes.length,
                    outFile.getAbsolutePath());

            postToDiscordIfConfigured(p, bytes, filename);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error(
                    "Failed to process screenshot from {} id={}: {}",
                    p.playerName,
                    p.id,
                    e.getMessage(),
                    e);
        }
    }

    private static void postToDiscordIfConfigured(
            PendingScreenshot p, byte[] pngBytes, String filename) {
        String webhookUrl = System.getProperty(DISCORD_WEBHOOK_PROPERTY);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put(
                    "content",
                    "Screenshot from `" + p.playerName + "` (online ID: " + p.playerOnlineId + ")");
            String payloadJson = MAPPER.writeValueAsString(body);

            String boundary = "----StormScreenshotBoundary" + System.nanoTime();
            byte[] multipart = buildMultipart(boundary, payloadJson, filename, pngBytes);

            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(webhookUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                            .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 != 2) {
                LOGGER.warn(
                        "Discord webhook returned HTTP {} for screenshot from {}: {}",
                        status,
                        p.playerName,
                        resp.body());
            } else {
                LOGGER.info(
                        "Posted screenshot from {} to Discord webhook ({} bytes)",
                        p.playerName,
                        pngBytes.length);
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to post screenshot from {} to Discord webhook: {}",
                    p.playerName,
                    e.getMessage(),
                    e);
        }
    }

    private static byte[] buildMultipart(
            String boundary, String payloadJson, String filename, byte[] pngBytes)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(pngBytes.length + 512);
        String crlf = "\r\n";
        buf.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        buf.write(
                ("Content-Disposition: form-data; name=\"payload_json\""
                                + crlf
                                + "Content-Type: application/json"
                                + crlf
                                + crlf)
                        .getBytes(StandardCharsets.UTF_8));
        buf.write(payloadJson.getBytes(StandardCharsets.UTF_8));
        buf.write(crlf.getBytes(StandardCharsets.UTF_8));
        buf.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        buf.write(
                ("Content-Disposition: form-data; name=\"files[0]\"; filename=\""
                                + filename
                                + "\""
                                + crlf
                                + "Content-Type: image/png"
                                + crlf
                                + crlf)
                        .getBytes(StandardCharsets.UTF_8));
        buf.write(pngBytes);
        buf.write(crlf.getBytes(StandardCharsets.UTF_8));
        buf.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return buf.toByteArray();
    }
}
