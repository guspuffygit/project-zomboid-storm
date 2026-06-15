package io.pzstorm.storm.screenshot;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.event.core.OnClientCommand;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;

/**
 * Server-side handler for screenshot chunks streamed from the client. Chunks are dropped into a
 * {@link ConcurrentHashMap} on the main thread (cheap reference store, no decode work), and the
 * final concat + base64 decode + file write run on a background executor.
 */
public class StormScreenshotReceiver {

    static final String DISCORD_WEBHOOK_PROPERTY = "storm.screenshot.discord.webhook";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "StormScreenshotProcessor");
                        t.setDaemon(true);
                        return t;
                    });

    private static final ConcurrentHashMap<String, PendingScreenshot> PENDING =
            new ConcurrentHashMap<>();

    private static final class PendingScreenshot {
        final String playerName;
        final short playerOnlineId;
        final String id;
        final int total;
        final String[] chunks;
        final AtomicInteger received = new AtomicInteger(0);

        PendingScreenshot(String playerName, short playerOnlineId, String id, int total) {
            this.playerName = playerName;
            this.playerOnlineId = playerOnlineId;
            this.id = id;
            this.total = total;
            this.chunks = new String[total];
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
        String data = event.getData();

        if (id == null || data == null || indexBox == null || totalBox == null) {
            LOGGER.warn("Invalid screenshot chunk from {}", player.getUsername());
            return;
        }

        int index = indexBox;
        int total = totalBox;
        if (total <= 0 || index <= 0 || index > total) {
            LOGGER.warn(
                    "Invalid screenshot chunk index={} total={} from {}",
                    index,
                    total,
                    player.getUsername());
            return;
        }

        String playerName = player.getUsername();
        short playerOnlineId = player.getOnlineID();
        String key = playerName + "_" + id;

        PendingScreenshot pending =
                PENDING.computeIfAbsent(
                        key, k -> new PendingScreenshot(playerName, playerOnlineId, id, total));

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
                stored = true;
            }
        }
        if (!stored) {
            return;
        }

        int got = pending.received.incrementAndGet();
        LOGGER.debug("Screenshot chunk {}/{} from {}", index, total, playerName);

        if (got >= total) {
            PENDING.remove(key, pending);
            EXECUTOR.submit(() -> processCompleted(pending));
        }
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
