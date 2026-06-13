package io.pzstorm.storm.screenshot;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.screenshot.commands.ScreenshotChunkCommand;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
        final String id;
        final int total;
        final String[] chunks;
        final AtomicInteger received = new AtomicInteger(0);

        PendingScreenshot(String playerName, String id, int total) {
            this.playerName = playerName;
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
        String key = playerName + "_" + id;

        PendingScreenshot pending =
                PENDING.computeIfAbsent(key, k -> new PendingScreenshot(playerName, id, total));

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
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error(
                    "Failed to process screenshot from {} id={}: {}",
                    p.playerName,
                    p.id,
                    e.getMessage(),
                    e);
        }
    }
}
