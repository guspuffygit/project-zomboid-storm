package io.pzstorm.launcher;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Minimal Steamworks UGC binding via java.lang.foreign — no SDK jar, no JNI glue. Lets the launcher
 * ask the RUNNING Steam client to download/update workshop items into Steam's own content directory
 * before the game starts. We never write into steamapps ourselves; Steam does, through its
 * supported API.
 *
 * <p>Must run with working directory = the game install (steam_appid.txt) and with the same OS's
 * Steam client running. The launcher therefore invokes this in a child JVM ({@code --steam-update})
 * spawned with cwd = gameDir.
 */
public final class SteamUgc implements AutoCloseable {

    // EItemState bits (Steamworks ISteamUGC)
    static final int STATE_INSTALLED = 4;
    static final int STATE_SUBSCRIBED = 1;
    static final int STATE_NEEDS_UPDATE = 8;
    static final int STATE_DOWNLOADING = 16;
    static final int STATE_DOWNLOAD_PENDING = 32;

    private static final long POLL_MILLIS = 500;
    private static final long STALL_TIMEOUT_MILLIS = 180_000;
    private static final long SUBSCRIBE_TIMEOUT_MILLIS = 30_000;

    private final Arena arena;
    private final MethodHandle shutdown;
    private final MethodHandle runCallbacks;
    private final MethodHandle subscribeItem;
    private final MethodHandle downloadItem;
    private final MethodHandle getItemState;
    private final MethodHandle getItemDownloadInfo;
    private final MemorySegment ugc;

    private SteamUgc(Arena arena, SymbolLookup lib) throws Throwable {
        this.arena = arena;
        Linker linker = Linker.nativeLinker();

        MethodHandle initFlat =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_InitFlat",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        true);
        shutdown = downcall(linker, lib, "SteamAPI_Shutdown", FunctionDescriptor.ofVoid(), false);
        runCallbacks =
                downcall(linker, lib, "SteamAPI_RunCallbacks", FunctionDescriptor.ofVoid(), false);
        MethodHandle ugcAccessor =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_SteamUGC_v021",
                        FunctionDescriptor.of(ValueLayout.ADDRESS),
                        false);
        subscribeItem =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ISteamUGC_SubscribeItem",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                        false);
        downloadItem =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ISteamUGC_DownloadItem",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_BOOLEAN),
                        false);
        getItemState =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ISteamUGC_GetItemState",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                        false);
        getItemDownloadInfo =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ISteamUGC_GetItemDownloadInfo",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS),
                        false);

        if (initFlat != null) {
            MemorySegment errMsg = arena.allocate(1024);
            int result = (int) initFlat.invoke(errMsg);
            if (result != 0) {
                throw new SteamException(
                        "SteamAPI_Init failed ("
                                + result
                                + "): "
                                + errMsg.getString(0)
                                + " — is Steam running?");
            }
        } else {
            MethodHandle initSafe =
                    downcall(
                            linker,
                            lib,
                            "SteamAPI_InitSafe",
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN),
                            false);
            if (!(boolean) initSafe.invoke()) {
                throw new SteamException("SteamAPI_Init failed — is Steam running?");
            }
        }
        this.ugc = (MemorySegment) ugcAccessor.invoke();
        if (ugc.equals(MemorySegment.NULL)) {
            throw new SteamException("SteamUGC interface unavailable");
        }
    }

    private static MethodHandle downcall(
            Linker linker,
            SymbolLookup lib,
            String name,
            FunctionDescriptor descriptor,
            boolean optional) {
        Optional<MemorySegment> symbol = lib.find(name);
        if (symbol.isEmpty()) {
            if (optional) {
                return null;
            }
            throw new SteamException("steam_api is missing export " + name);
        }
        return linker.downcallHandle(symbol.get(), descriptor);
    }

    public static class SteamException extends RuntimeException {
        public SteamException(String message) {
            super(message);
        }
    }

    /** Locates the steam_api library inside the game install. */
    static Path findLibrary(Path gameDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> candidates;
        if (os.contains("win")) {
            candidates = List.of(gameDir.resolve("steam_api64.dll"));
        } else if (os.contains("mac")) {
            candidates = List.of(gameDir.resolve("libsteam_api.dylib"));
        } else {
            candidates =
                    List.of(
                            gameDir.resolve("linux64").resolve("libsteam_api.so"),
                            gameDir.resolve("libsteam_api.so"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new SteamException("steam_api library not found under " + gameDir);
    }

    public static SteamUgc connect(Path gameDir) {
        Arena arena = Arena.ofShared();
        try {
            return new SteamUgc(arena, SymbolLookup.libraryLookup(findLibrary(gameDir), arena));
        } catch (SteamException e) {
            arena.close();
            throw e;
        } catch (Throwable t) {
            arena.close();
            throw new SteamException("Steam binding failed: " + t);
        }
    }

    /**
     * Bring one workshop item to the exact state the game's join gate demands: {@code
     * Subscribed|Installed} with no update pending (ConnectToServerState requires equality with 5 —
     * a merely-downloaded, unsubscribed item still triggers the in-game prompt screen). Subscribing
     * is what the vanilla join flow does for unsubscribed items, and it makes Steam keep the item
     * current.
     */
    public boolean updateItem(long itemId, Consumer<String> progress) throws InterruptedException {
        int state = itemState(itemId);
        if (isJoinReady(state)) {
            progress.accept("item " + itemId + " already up to date");
            return true;
        }
        if ((state & STATE_SUBSCRIBED) == 0) {
            try {
                subscribeItem.invoke(ugc, itemId);
            } catch (Throwable t) {
                throw new SteamException("SubscribeItem(" + itemId + ") failed: " + t);
            }
            progress.accept("item " + itemId + " subscribing …");
            long deadline = System.currentTimeMillis() + SUBSCRIBE_TIMEOUT_MILLIS;
            while ((itemState(itemId) & STATE_SUBSCRIBED) == 0) {
                if (System.currentTimeMillis() > deadline) {
                    progress.accept(
                            "item "
                                    + itemId
                                    + " FAILED (subscribe timed out — invalid"
                                    + " id or Steam offline?)");
                    return false;
                }
                pumpCallbacks();
                Thread.sleep(POLL_MILLIS);
            }
        }
        boolean accepted;
        try {
            accepted = (boolean) downloadItem.invoke(ugc, itemId, true);
        } catch (Throwable t) {
            throw new SteamException("DownloadItem(" + itemId + ") failed: " + t);
        }
        if (!accepted) {
            progress.accept("item " + itemId + " rejected by Steam (invalid id?)");
            return false;
        }
        progress.accept("item " + itemId + " update requested");

        long lastProgressBytes = -1;
        long lastChange = System.currentTimeMillis();
        while (true) {
            pumpCallbacks();
            state = itemState(itemId);
            long[] downloaded = downloadInfo(itemId);
            if ((state & (STATE_DOWNLOADING | STATE_DOWNLOAD_PENDING)) == 0) {
                boolean ok = isJoinReady(state);
                progress.accept(
                        "item " + itemId + (ok ? " up to date" : " FAILED (state=" + state + ")"));
                return ok;
            }
            if (downloaded != null && downloaded[0] != lastProgressBytes) {
                lastProgressBytes = downloaded[0];
                lastChange = System.currentTimeMillis();
                if (downloaded[1] > 0) {
                    progress.accept(
                            "item "
                                    + itemId
                                    + " downloading "
                                    + downloaded[0]
                                    + "/"
                                    + downloaded[1]
                                    + " bytes");
                }
            } else if (System.currentTimeMillis() - lastChange > STALL_TIMEOUT_MILLIS) {
                progress.accept(
                        "item "
                                + itemId
                                + " STALLED (no progress for "
                                + (STALL_TIMEOUT_MILLIS / 1000)
                                + "s) — check Steam downloads");
                return false;
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    /** The game's join gate requires exactly Subscribed|Installed and nothing pending. */
    static boolean isJoinReady(int state) {
        return (state & STATE_SUBSCRIBED) != 0
                && (state & STATE_INSTALLED) != 0
                && (state & (STATE_NEEDS_UPDATE | STATE_DOWNLOADING | STATE_DOWNLOAD_PENDING)) == 0;
    }

    private int itemState(long itemId) {
        try {
            return (int) getItemState.invoke(ugc, itemId);
        } catch (Throwable t) {
            throw new SteamException("GetItemState(" + itemId + ") failed: " + t);
        }
    }

    /** [bytesDownloaded, bytesTotal] or null when Steam has no download info yet. */
    private long[] downloadInfo(long itemId) {
        try {
            MemorySegment done = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment total = arena.allocate(ValueLayout.JAVA_LONG);
            boolean known = (boolean) getItemDownloadInfo.invoke(ugc, itemId, done, total);
            if (!known) {
                return null;
            }
            return new long[] {
                done.get(ValueLayout.JAVA_LONG, 0), total.get(ValueLayout.JAVA_LONG, 0)
            };
        } catch (Throwable t) {
            throw new SteamException("GetItemDownloadInfo(" + itemId + ") failed: " + t);
        }
    }

    private void pumpCallbacks() {
        try {
            runCallbacks.invoke();
        } catch (Throwable t) {
            throw new SteamException("RunCallbacks failed: " + t);
        }
    }

    @Override
    public void close() {
        try {
            shutdown.invoke();
        } catch (Throwable ignored) {
            // process exits right after; nothing to recover
        }
        arena.close();
    }
}
