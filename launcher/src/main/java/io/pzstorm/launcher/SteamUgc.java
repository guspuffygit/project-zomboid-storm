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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** DownloadItemResult_t: k_iSteamUGCCallbacks (3400) + 6. */
    static final int DOWNLOAD_ITEM_RESULT_CALLBACK = 3406;

    /** k_EResultOK. */
    static final int RESULT_OK = 1;

    private static final long POLL_MILLIS = 500;
    private static final long STALL_TIMEOUT_MILLIS = 180_000;
    private static final long SUBSCRIBE_TIMEOUT_MILLIS = 30_000;

    /**
     * Settle window after DownloadItem is accepted: Steam gets this long to either deliver a
     * DownloadItemResult_t or start reporting download activity before we conclude the item was
     * already current. The final GetItemState check still gates the verdict.
     */
    private static final long SETTLE_GRACE_MILLIS = 5_000;

    /** Prints every drained callback (id, size) — for field-debugging the dispatch plumbing. */
    private static final boolean DEBUG = Boolean.getBoolean("storm.steamugc.debug");

    private final Arena arena;
    private final MethodHandle shutdown;
    private final MethodHandle runCallbacks;
    private final MethodHandle subscribeItem;
    private final MethodHandle downloadItem;
    private final MethodHandle getItemState;
    private final MethodHandle getItemDownloadInfo;
    private final MethodHandle dispatchRunFrame;
    private final MethodHandle dispatchGetNext;
    private final MethodHandle dispatchFreeLast;
    private final MemorySegment ugc;
    private final MemorySegment callbackMsg;

    /** Steam pipe handle for manual callback dispatch; 0 = unavailable, use the grace fallback. */
    private final int pipe;

    /** Steamworks callback structs are pack(4) on linux/macOS, pack(8) on Windows. */
    private final boolean packSmall;

    /** DownloadItemResult_t received so far: workshop item id -> EResult. */
    private final Map<Long, Integer> downloadResults = new HashMap<>();

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
        MethodHandle dispatchInit =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ManualDispatch_Init",
                        FunctionDescriptor.ofVoid(),
                        true);
        MethodHandle getSteamPipe =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_GetHSteamPipe",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT),
                        true);
        dispatchRunFrame =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ManualDispatch_RunFrame",
                        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
                        true);
        dispatchGetNext =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ManualDispatch_GetNextCallback",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS),
                        true);
        dispatchFreeLast =
                downcall(
                        linker,
                        lib,
                        "SteamAPI_ManualDispatch_FreeLastCallback",
                        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
                        true);
        boolean manualDispatch =
                dispatchInit != null
                        && getSteamPipe != null
                        && dispatchRunFrame != null
                        && dispatchGetNext != null
                        && dispatchFreeLast != null;
        if (manualDispatch) {
            // current SDKs require this BEFORE SteamAPI_Init to reroute callbacks to the manual
            // queue; the PZ mac depot's older steam_api rejects the pre-init call ("must init
            // library first"), so it is repeated after init below
            dispatchInit.invoke();
        }

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
        if (manualDispatch) {
            // second call for the older mac steam_api, which only accepts it after init; on
            // current SDKs the dispatch flag is already set and this is a no-op
            dispatchInit.invoke();
        }
        this.ugc = (MemorySegment) ugcAccessor.invoke();
        if (ugc.equals(MemorySegment.NULL)) {
            throw new SteamException("SteamUGC interface unavailable");
        }
        this.pipe = manualDispatch ? (int) getSteamPipe.invoke() : 0;
        // CallbackMsg_t: int32 user, int32 callbackId, param*, int32 paramLen — 24 bytes on x64
        this.callbackMsg = arena.allocate(24);
        String os = System.getProperty("os.name", "").toLowerCase();
        this.packSmall = !os.contains("win");
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
     *
     * <p>Always calls DownloadItem, even when GetItemState claims the item is current: that state
     * only reflects the Steam client's CACHED metadata, which is stale from the moment a new
     * version is published until Steam happens to re-poll the workshop. DownloadItem (high
     * priority) forces the check against the latest published manifest right now; its
     * DownloadItemResult_t callback is the authoritative completion signal.
     */
    public boolean updateItem(long itemId, Consumer<String> progress) throws InterruptedException {
        int state = itemState(itemId);
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
        downloadResults.remove(itemId);
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
        progress.accept("item " + itemId + " checking for updates …");

        boolean sawDownload = false;
        long lastProgressBytes = -1;
        long lastActivity = System.currentTimeMillis();
        while (true) {
            pumpCallbacks();
            state = itemState(itemId);
            if ((state & (STATE_DOWNLOADING | STATE_DOWNLOAD_PENDING)) != 0) {
                sawDownload = true;
                long[] downloaded = downloadInfo(itemId);
                if (downloaded != null && downloaded[0] != lastProgressBytes) {
                    lastProgressBytes = downloaded[0];
                    lastActivity = System.currentTimeMillis();
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
                } else if (System.currentTimeMillis() - lastActivity > STALL_TIMEOUT_MILLIS) {
                    progress.accept(
                            "item "
                                    + itemId
                                    + " STALLED (no progress for "
                                    + (STALL_TIMEOUT_MILLIS / 1000)
                                    + "s) — check Steam downloads");
                    return false;
                }
            } else {
                if (sawDownload) {
                    // busy -> idle transition: Steam finished (or aborted) the install; the
                    // final state check decides which
                    return reportFinalState(itemId, sawDownload, progress);
                }
                Integer result = downloadResults.get(itemId);
                if (result != null) {
                    if (result != RESULT_OK) {
                        progress.accept(
                                "item "
                                        + itemId
                                        + " FAILED (Steam download result "
                                        + result
                                        + ")");
                        return false;
                    }
                    return reportFinalState(itemId, sawDownload, progress);
                }
                if (System.currentTimeMillis() - lastActivity > SETTLE_GRACE_MILLIS) {
                    // no result callback and no download ever started: the item was current
                    return reportFinalState(itemId, sawDownload, progress);
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private boolean reportFinalState(long itemId, boolean sawDownload, Consumer<String> progress) {
        int state = itemState(itemId);
        boolean ok = isJoinReady(state);
        if (ok) {
            progress.accept("item " + itemId + (sawDownload ? " updated" : " up to date"));
        } else {
            progress.accept("item " + itemId + " FAILED (state=" + state + ")");
        }
        return ok;
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

    /**
     * With manual dispatch (any steam_api from the last several years) this drains the callback
     * queue and records DownloadItemResult_t payloads; without it, it falls back to the classic
     * RunCallbacks pump and {@link #updateItem} settles on a grace timer instead.
     */
    private void pumpCallbacks() {
        try {
            if (pipe == 0) {
                runCallbacks.invoke();
                return;
            }
            dispatchRunFrame.invoke(pipe);
            while ((boolean) dispatchGetNext.invoke(pipe, callbackMsg)) {
                try {
                    int callbackId = callbackMsg.get(ValueLayout.JAVA_INT, 4);
                    int paramLen = callbackMsg.get(ValueLayout.JAVA_INT, 16);
                    if (DEBUG) {
                        System.out.println(
                                "[debug] callback id=" + callbackId + " len=" + paramLen);
                    }
                    if (callbackId == DOWNLOAD_ITEM_RESULT_CALLBACK
                            && paramLen >= (packSmall ? 16 : 24)) {
                        MemorySegment data =
                                callbackMsg.get(ValueLayout.ADDRESS, 8).reinterpret(paramLen);
                        downloadResults.put(
                                downloadResultItemId(data, packSmall),
                                downloadResultCode(data, packSmall));
                    }
                } finally {
                    dispatchFreeLast.invoke(pipe);
                }
            }
        } catch (Throwable t) {
            throw new SteamException("Steam callback pump failed: " + t);
        }
    }

    /**
     * DownloadItemResult_t is AppId_t (int32), PublishedFileId_t (int64), EResult (int32). Valve
     * packs callback structs at 4 bytes on linux/macOS and 8 on Windows, which moves the 64-bit
     * file id to offset 4 or 8 respectively.
     */
    static long downloadResultItemId(MemorySegment data, boolean packSmall) {
        return data.get(ValueLayout.JAVA_LONG_UNALIGNED, packSmall ? 4 : 8);
    }

    static int downloadResultCode(MemorySegment data, boolean packSmall) {
        return data.get(ValueLayout.JAVA_INT_UNALIGNED, packSmall ? 12 : 16);
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
