package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import zombie.GameWindow;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.population.OutfitManager;
import zombie.debug.DebugLog;
import zombie.gameStates.GameLoadingState;
import zombie.network.GameClient;
import zombie.network.NetChecksum;

/**
 * Diagnoses the silent load-hang behind most "connection failed while loading" reports. The
 * multiplayer loading thread ({@code GameLoadingState.loader}) parks in waits that have no timeout
 * — {@code assetLock1}/{@code assetLock2} only release when {@code
 * OutfitManager.isLoadingClothingItems()} / {@code ModelManager.isLoadingAnimations()} and {@code
 * GameWindow.fileSystem.hasWork()} all go quiet — so one wedged asset job freezes loading forever
 * with nothing in any log. The client then never sends its Lua checksum, the server reaps the
 * connection in stage {@code checksum} after {@code StalledConnectionReaper}'s budget, and the
 * player sees a bare "connection failed" (ATF reaps 40–80 of these per 6 hours; see report
 * 8h02l4jb93, 2026-08-09).
 *
 * <p>A daemon thread samples the loader every {@link #POLL_MS} while it is alive: a heartbeat line
 * every {@link #HEARTBEAT_MS} (stage string + the wait-gate flags), and once nothing has changed
 * for {@link #STALL_AFTER_MS} a full stall dump — loader and main-thread stacks, the {@code
 * FileSystemImpl} task queues with the wedged task's class and file (read reflectively, so a PZ
 * update that renames those private fields degrades to a note instead of breaking), and the asset
 * worker pool's stacks. Everything is mirrored through {@link DebugLog} because the launcher's
 * "Send Logs" bundle captures {@code Zomboid/Logs/*_DebugLog.txt} but not Storm's own log
 * directory.
 *
 * <p>Reads public statics and thread stacks only — it never touches game state, takes no game
 * locks, and every poll is fenced by a catch-all so a surprise from a game update degrades to a
 * single warning, never a broken client. Registered by {@code StormLauncher} on client JVMs only.
 */
public final class ClientLoadingWatchdog {

    static final long POLL_MS = 5_000L;

    /** No fingerprint change for this long counts as a stall. */
    static final long STALL_AFTER_MS = 60_000L;

    /** While stalled, re-dump at most this often. */
    static final long STALL_REDUMP_MS = 60_000L;

    static final long HEARTBEAT_MS = 30_000L;

    /** First poll waits this long so game statics initialize on the game's own schedule. */
    static final long STARTUP_DELAY_MS = 15_000L;

    private static final int MAX_STACK_FRAMES = 40;

    private static final int MAX_QUEUE_TASKS = 8;

    private static final String TAG = "[StormLoadWatchdog] ";

    private static Thread watchdog;

    private ClientLoadingWatchdog() {}

    /** Idempotent; called reflectively by {@code StormLauncher} on client JVMs. */
    public static synchronized void start() {
        if (watchdog != null) {
            return;
        }
        watchdog = new Thread(ClientLoadingWatchdog::run, "StormLoadingWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        LOGGER.info("Client loading watchdog armed");
    }

    private static void run() {
        sleep(STARTUP_DELAY_MS);
        boolean loadActive = false;
        long loadStartMs = 0L;
        String fingerprint = null;
        long lastChangeMs = 0L;
        long lastHeartbeatMs = 0L;
        long lastDumpMs = 0L;
        boolean stalled = false;
        boolean warnedPollFailure = false;
        while (true) {
            try {
                Thread loader = GameLoadingState.loader;
                long now = System.currentTimeMillis();
                if (loader == null || !loader.isAlive()) {
                    if (loadActive) {
                        emit(
                                "loading ended after "
                                        + (now - loadStartMs) / 1000L
                                        + "s ("
                                        + (stalled ? "was stalled; " : "")
                                        + flagsLine()
                                        + ")");
                    }
                    loadActive = false;
                    fingerprint = null;
                    stalled = false;
                } else {
                    if (!loadActive) {
                        loadActive = true;
                        loadStartMs = now;
                        lastChangeMs = now;
                        lastHeartbeatMs = 0L;
                        lastDumpMs = 0L;
                        emit("loading started (" + flagsLine() + ")");
                    }
                    StackTraceElement[] loaderStack = loader.getStackTrace();
                    String current =
                            GameLoadingState.gameLoadingString
                                    + '|'
                                    + flagsLine()
                                    + '|'
                                    + stackString(loaderStack);
                    if (!current.equals(fingerprint)) {
                        fingerprint = current;
                        lastChangeMs = now;
                        if (stalled) {
                            stalled = false;
                            emit("progress resumed: " + flagsLine());
                        }
                    }
                    if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
                        lastHeartbeatMs = now;
                        emit(
                                "loading "
                                        + (now - loadStartMs) / 1000L
                                        + "s, stage=\""
                                        + GameLoadingState.gameLoadingString
                                        + "\", "
                                        + flagsLine()
                                        + ", loaderAt="
                                        + topFrame(loaderStack));
                    }
                    if (now - lastChangeMs >= STALL_AFTER_MS
                            && now - lastDumpMs >= STALL_REDUMP_MS) {
                        lastDumpMs = now;
                        stalled = true;
                        dumpStall(loader, loaderStack, now - lastChangeMs, now - loadStartMs);
                    }
                }
                warnedPollFailure = false;
            } catch (Throwable t) {
                if (!warnedPollFailure) {
                    warnedPollFailure = true;
                    LOGGER.warn("Client loading watchdog poll failed", t);
                }
            }
            sleep(POLL_MS);
        }
    }

    /**
     * The exact conditions {@code GameLoadingState.update()} needs before it will release the
     * loader from an asset wait, plus the checksum handshake state the server-side reap stage
     * reflects.
     */
    private static String flagsLine() {
        StringBuilder flags = new StringBuilder();
        try {
            ModelManager models = ModelManager.instance;
            flags.append("loadingAnims=")
                    .append(models == null ? "?" : models.isLoadingAnimations());
        } catch (Throwable t) {
            flags.append("loadingAnims=err");
        }
        try {
            flags.append(" fsHasWork=")
                    .append(GameWindow.fileSystem == null ? "?" : GameWindow.fileSystem.hasWork());
        } catch (Throwable t) {
            flags.append(" fsHasWork=err");
        }
        try {
            OutfitManager outfits = OutfitManager.instance;
            flags.append(" loadingClothing=")
                    .append(outfits == null ? "?" : outfits.isLoadingClothingItems());
        } catch (Throwable t) {
            flags.append(" loadingClothing=err");
        }
        try {
            flags.append(" checksum=")
                    .append(NetChecksum.comparer.state)
                    .append(" checksumValid=")
                    .append(GameClient.checksumValid)
                    .append(" serverDisconnected=")
                    .append(GameWindow.serverDisconnected);
        } catch (Throwable t) {
            flags.append(" checksum=err");
        }
        return flags.toString();
    }

    private static void dumpStall(
            Thread loader, StackTraceElement[] loaderStack, long stalledMs, long loadingMs) {
        emit(
                "STALL: no loading progress for "
                        + stalledMs / 1000L
                        + "s ("
                        + loadingMs / 1000L
                        + "s since load start), stage=\""
                        + GameLoadingState.gameLoadingString
                        + "\", "
                        + flagsLine());
        emitStack(loader.getName() + " [" + loader.getState() + "]", loaderStack);
        Thread gameThread = GameWindow.gameThread;
        if (gameThread != null && gameThread.isAlive()) {
            emitStack(
                    gameThread.getName() + " (main) [" + gameThread.getState() + "]",
                    gameThread.getStackTrace());
        }
        emitFileSystemQueues();
        emitWorkerThreads(loader, gameThread);
        emit("STALL dump end");
    }

    /**
     * Names the wedged asset: {@code FileSystemImpl}'s {@code added}/{@code pending}/{@code
     * inProgress} queues hold the {@code FileTask}s the {@code hasWork()} gate is waiting on, and a
     * task's string fields carry the file path it is for. All private, so read reflectively and
     * best-effort — the lists are owned by other threads, and a torn read here only costs detail in
     * a diagnostic line.
     */
    private static void emitFileSystemQueues() {
        Object fileSystem = GameWindow.fileSystem;
        if (fileSystem == null) {
            return;
        }
        for (String queue : new String[] {"added", "pending", "inProgress"}) {
            try {
                Field field = fileSystem.getClass().getDeclaredField(queue);
                field.setAccessible(true);
                List<?> items = new ArrayList<>((List<?>) field.get(fileSystem));
                StringBuilder line =
                        new StringBuilder("fileSystem." + queue + " size=" + items.size());
                int shown = 0;
                for (Object item : items) {
                    if (shown++ == MAX_QUEUE_TASKS) {
                        line.append(" …");
                        break;
                    }
                    line.append(shown == 1 ? ": " : ", ").append(describeTask(item));
                }
                emit(line.toString());
            } catch (Throwable t) {
                emit("fileSystem." + queue + " unreadable: " + t);
            }
        }
    }

    /** {@code AsyncItem} → its task's simple class name plus any string fields (file paths). */
    static String describeTask(Object asyncItem) {
        try {
            Field taskField = asyncItem.getClass().getDeclaredField("task");
            taskField.setAccessible(true);
            Object task = taskField.get(asyncItem);
            if (task == null) {
                return "null";
            }
            StringBuilder text = new StringBuilder(task.getClass().getSimpleName());
            for (Field field : task.getClass().getDeclaredFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(task);
                if (value != null) {
                    text.append('(').append(value).append(')');
                    break;
                }
            }
            return text.toString();
        } catch (Throwable t) {
            return "unreadable:" + t.getClass().getSimpleName();
        }
    }

    /**
     * Stacks of the asset workers ({@code FileSystemImpl}'s executor uses default {@code
     * pool-N-thread-M} names) — when {@code fsHasWork} is stuck true, one of these holds the
     * culprit frame.
     */
    private static void emitWorkerThreads(Thread loader, Thread gameThread) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (thread == loader || thread == gameThread || thread == Thread.currentThread()) {
                continue;
            }
            if (!thread.getName().startsWith("pool-")) {
                continue;
            }
            StackTraceElement[] stack = entry.getValue();
            // An idle executor worker parks in the queue poll; only busy workers are interesting.
            if (stack.length == 0 || stackString(stack).contains("getTask")) {
                continue;
            }
            emitStack(thread.getName() + " [" + thread.getState() + "]", stack);
        }
    }

    private static void emitStack(String header, StackTraceElement[] stack) {
        StringBuilder text = new StringBuilder(header);
        int frames = Math.min(stack.length, MAX_STACK_FRAMES);
        for (int i = 0; i < frames; i++) {
            text.append("\n    at ").append(stack[i]);
        }
        if (stack.length > frames) {
            text.append("\n    … ").append(stack.length - frames).append(" more");
        }
        emit(text.toString());
    }

    static String topFrame(StackTraceElement[] stack) {
        return stack.length == 0 ? "?" : stack[0].toString();
    }

    static String stackString(StackTraceElement[] stack) {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement frame : stack) {
            text.append(frame).append(';');
        }
        return text.toString();
    }

    /**
     * Storm's log for operators tailing storm/main.log, mirrored into the vanilla DebugLog because
     * that is the only client log the launcher's "Send Logs" bundle ships.
     */
    private static void emit(String message) {
        LOGGER.info(TAG + message);
        try {
            DebugLog.log(TAG + message);
        } catch (Throwable ignored) {
            // vanilla logger not ready or changed; the Storm log line above already landed
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
