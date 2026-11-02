package io.pzstorm.storm.advice.puddlebatch;

import io.pzstorm.storm.logging.StormLogger;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.FBORenderSnow;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * Replacement body for {@code FBORenderCell.renderPuddles(int)}, wired in by {@code
 * PuddleBatchRenderPatch}. Vanilla filters, lights and packs every puddle square of every on-screen
 * chunk level on every frame. This pass keeps the packed vertices of each chunk level in a {@link
 * PuddleBatch} and touches a batch only when its inputs changed.
 *
 * <p><b>Pass.</b> The vanilla gates run first. Then, per level and per on-screen chunk with a
 * non-empty puddle list, the batch is rebuilt, patched in place, or reused as is, and joins the
 * draw command of its level. Commands are submitted after the whole pass succeeded, in level order,
 * so the draw order matches the one {@code drawPuddles} per level that vanilla emits.
 *
 * <p><b>Rebuild.</b> A batch is rebuilt when a hook marked it invalid, when the puddle list size or
 * the cutaway flag mask of the chunk level changed, or when its backstop frame arrived. The build
 * applies the vanilla square filter minus the on-screen test, then packs with the vanilla {@code
 * updateLighting} and {@code RenderData.addSquare} into scratch storage. The camera jiggle that
 * {@code addSquare} adds to every position is subtracted again, and the render thread applies the
 * jiggle of the drawn frame as a model-view translation.
 *
 * <p><b>In-place patches.</b> A level invalidation re-reads the vertex lights. A camera chunk
 * crossing adds one constant to every vertex depth, because the depth of a square relative to the
 * camera chunk is linear in the chunk offset.
 *
 * <p><b>Vanilla fallback per pass.</b> The vanilla body runs for a pass whose player is not the
 * frame-state player, during a chunk-texture caching pass, or when the chunk map is taller than the
 * batch table. {@code addSquare} reads the frame-state player and takes another branch while
 * caching, so neither case can be packed here.
 *
 * <p><b>Fail soft.</b> Any {@link Throwable} releases the commands built so far, logs once, latches
 * {@link #failed} and returns {@code false}, so the vanilla body draws that frame and every frame
 * after. The render-thread draw and the hooks latch the same flag.
 *
 * <p>Game thread only, except {@link #fail}, {@link #hasFailed()}, {@link #stats()} and {@link
 * #setEnabled(boolean)}.
 */
public final class PuddleBatchRenderer {

    static final int MAX_LEVELS = 32;

    private static final int SWEEP_INTERVAL_FRAMES = 512;
    private static final int SWEEP_MAX_AGE_FRAMES = 2048;

    /** Light indices of the four packed vertices, as {@code IsoPuddlesGeometry.updateLighting}. */
    private static final int[] LIGHT_ORDER = {0, 3, 2, 1};

    static final PuddleBatchTable TABLE = new PuddleBatchTable();
    static final ConcurrentLinkedQueue<PuddleBatch> RETIRED = new ConcurrentLinkedQueue<>();

    private static final int INTERVAL =
            Math.max(1, Integer.getInteger("storm.clientperf.puddles.cacheFrames", 60));

    private static volatile boolean enabled =
            Boolean.parseBoolean(System.getProperty("storm.clientperf.puddles.cache", "true"));

    /** Permanent revert-to-vanilla latch. */
    private static volatile boolean failed;

    private static final PuddleBatchDrawCommand[] COMMANDS = new PuddleBatchDrawCommand[MAX_LEVELS];

    private static WeakReference<IsoCell> lastCell = new WeakReference<>(null);
    private static int lastQuality = Integer.MIN_VALUE;
    private static int lastPerfPuddles = Integer.MIN_VALUE;
    private static boolean lastNoLighting;
    private static int lastSweepFrame;
    private static long pass;

    private PuddleBatchRenderer() {}

    /** Returns true when this pass replaced the vanilla body, false when vanilla must run. */
    public static boolean render(Object fboRenderCell, int playerIndex) {
        if (failed || !enabled) {
            if (!TABLE.isEmpty()) {
                releaseEverything();
            }
            return false;
        }
        try {
            return pass((FBORenderCell) fboRenderCell, playerIndex);
        } catch (Throwable t) {
            discardCommands();
            fail("game-thread pass", t);
            return false;
        }
    }

    private static boolean pass(FBORenderCell fbo, int playerIndex) throws Throwable {
        int frame = IsoWorld.instance.getFrameNo();
        sweep(frame);

        if (!IsoPuddles.getInstance().shouldRenderPuddles()
                || FBORenderSnow.getInstance().isSnowAnywhere()) {
            drainRetired();
            return true;
        }
        int maxZ = fbo.cell.chunkMap[playerIndex].maxHeight;
        if (Core.getInstance().getPerfPuddles() > 0) {
            maxZ = 0;
        }
        if (playerIndex != IsoCamera.frameState.playerIndex
                || FBORenderChunkManager.instance.isCaching()
                || maxZ >= MAX_LEVELS) {
            PuddleBatchStats.vanillaPasses++;
            return false;
        }

        PuddleBatchAccess.ensureInit();
        boolean noLighting = DebugOptions.instance.fboRenderChunk.nolighting.getValue();
        checkSettings(fbo.cell, noLighting);

        PlayerCamera camera = IsoCamera.cameras[playerIndex];
        float jx = camera.fixJigglyModelsX * camera.zoom;
        float jy = camera.fixJigglyModelsY * camera.zoom;
        int camChunkX =
                PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterX) / 8.0F);
        int camChunkY =
                PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterY) / 8.0F);
        long currentPass = ++pass;

        int built = 0;
        int reused = 0;
        int rebuildInvalid = 0;
        int rebuildList = 0;
        int rebuildMask = 0;
        int rebuildExpired = 0;
        int lightChecks = 0;
        int lightPatches = 0;
        int depthShifts = 0;
        int queuedRebuild = 0;
        int queuedLight = 0;
        int queuedShift = 0;
        int queuedRetry = 0;

        ArrayList<IsoChunk> chunks = PuddleBatchAccess.onScreenChunks(fbo, playerIndex);
        for (int z = 0; z <= maxZ; z++) {
            PuddleBatchDrawCommand command = null;
            for (int i = 0; i < chunks.size(); i++) {
                IsoChunk chunk = chunks.get(i);
                if (z < chunk.minLevel || z > chunk.maxLevel) {
                    continue;
                }
                FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                if (!renderLevels.isOnScreen(z)) {
                    continue;
                }
                List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
                if (squares.isEmpty()) {
                    continue;
                }
                FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
                long mask = PuddleBatch.flagMask(levelData.squareFlags[playerIndex]);
                PuddleBatch batch = TABLE.getOrCreate(chunk, playerIndex, z, frame);
                if (batch == null) {
                    throw new IllegalStateException(
                            "no batch slot for player " + playerIndex + " level " + z);
                }

                int cause = batch.rebuildCause(squares.size(), mask, frame);
                if (cause != PuddleBatch.REUSE) {
                    build(
                            batch,
                            chunk,
                            squares,
                            levelData,
                            playerIndex,
                            z,
                            mask,
                            jx,
                            jy,
                            camChunkX,
                            camChunkY,
                            frame);
                    built++;
                    if (cause == PuddleBatch.REBUILD_INVALID) {
                        rebuildInvalid++;
                    } else if (cause == PuddleBatch.REBUILD_LIST) {
                        rebuildList++;
                    } else if (cause == PuddleBatch.REBUILD_MASK) {
                        rebuildMask++;
                    } else {
                        rebuildExpired++;
                    }
                } else {
                    reused++;
                    if (batch.lightsDirty) {
                        lightChecks++;
                        if (relight(batch, playerIndex, noLighting)) {
                            lightPatches++;
                        }
                    }
                    if (batch.camChunkX != camChunkX || batch.camChunkY != camChunkY) {
                        if (shiftDepth(batch, chunk, z, camChunkX, camChunkY)) {
                            depthShifts++;
                        }
                    }
                }

                if (batch.count == 0) {
                    continue;
                }
                float[] snapshot = null;
                if (batch.needsUpload(currentPass)) {
                    snapshot = PuddleBatchPools.allocSnapshot();
                    int causes = batch.takeSnapshot(snapshot, currentPass);
                    if (causes == 0) {
                        queuedRetry++;
                    } else {
                        if ((causes & PuddleBatch.CAUSE_REBUILD) != 0) {
                            queuedRebuild++;
                        }
                        if ((causes & PuddleBatch.CAUSE_LIGHT) != 0) {
                            queuedLight++;
                        }
                        if ((causes & PuddleBatch.CAUSE_SHIFT) != 0) {
                            queuedShift++;
                        }
                    }
                }
                if (command == null) {
                    command = PuddleBatchPools.allocCommand().init(playerIndex, z, jx, jy);
                    COMMANDS[z] = command;
                }
                command.add(batch, batch.count, batch.version, snapshot);
            }
        }

        boolean submitted = false;
        for (int z = 0; z <= maxZ; z++) {
            PuddleBatchDrawCommand command = COMMANDS[z];
            if (command != null) {
                COMMANDS[z] = null;
                SpriteRenderer.instance.drawGeneric(command);
                submitted = true;
            }
        }
        if (!submitted) {
            drainRetired();
        }

        PuddleBatchStats.passes++;
        PuddleBatchStats.built += built;
        PuddleBatchStats.reused += reused;
        PuddleBatchStats.rebuildInvalid += rebuildInvalid;
        PuddleBatchStats.rebuildList += rebuildList;
        PuddleBatchStats.rebuildMask += rebuildMask;
        PuddleBatchStats.rebuildExpired += rebuildExpired;
        PuddleBatchStats.lightChecks += lightChecks;
        PuddleBatchStats.lightPatches += lightPatches;
        PuddleBatchStats.depthShifts += depthShifts;
        PuddleBatchStats.queuedRebuild += queuedRebuild;
        PuddleBatchStats.queuedLight += queuedLight;
        PuddleBatchStats.queuedShift += queuedShift;
        PuddleBatchStats.queuedRetry += queuedRetry;
        return true;
    }

    private static void build(
            PuddleBatch batch,
            IsoChunk chunk,
            List<IsoGridSquare> squares,
            FBORenderCutaways.ChunkLevelData levelData,
            int playerIndex,
            int z,
            long mask,
            float jx,
            float jy,
            int camChunkX,
            int camChunkY,
            int frame)
            throws Throwable {
        batch.beginBuild();
        batch.ensureCapacity(Math.min(squares.size(), PuddleBatch.MAX_SQUARES));
        PuddleBatchAccess.scratchClear();
        int listSize = squares.size();
        int n = 0;
        for (int j = 0; j < listSize; j++) {
            IsoGridSquare square = squares.get(j);
            if (square.getZ() != z || !levelData.shouldRenderSquare(playerIndex, square)) {
                continue;
            }
            IsoObject floor = square.getFloor();
            if (floor == null
                    || (PerformanceSettings.puddlesQuality < 2
                            && floor.getRenderInfo(playerIndex).layer
                                    == ObjectRenderLayer.TranslucentFloor)) {
                continue;
            }
            IsoPuddlesGeometry geometry = square.getPuddles();
            if (geometry == null || !geometry.shouldRender()) {
                continue;
            }
            if (n == PuddleBatch.MAX_SQUARES) {
                PuddleBatchStats.overCap++;
                break;
            }
            geometry.updateLighting(playerIndex);
            PuddleBatchAccess.scratchAddSquare(z, geometry);
            batch.squares[n++] = square;
        }
        if (PuddleBatchAccess.scratchCount() != n) {
            batch.failBuild();
            PuddleBatchStats.buildMismatches++;
            return;
        }
        if (n > 0) {
            batch.load(PuddleBatchAccess.scratchData(), n, jx, jy);
        }
        batch.finishBuild(
                listSize,
                mask,
                camChunkX,
                camChunkY,
                frame,
                INTERVAL,
                PuddleBatch.stagger(chunk.wx, chunk.wy, z, INTERVAL));
    }

    private static boolean relight(PuddleBatch batch, int playerIndex, boolean noLighting) {
        batch.lightsDirty = false;
        boolean changed = false;
        for (int s = 0; s < batch.count; s++) {
            IsoGridSquare square = (IsoGridSquare) batch.squares[s];
            for (int v = 0; v < 4; v++) {
                int light = noLighting ? -1 : square.getVertLight(LIGHT_ORDER[v], playerIndex);
                changed |= batch.setLight(s, v, light);
            }
        }
        if (changed) {
            batch.markDirty(PuddleBatch.CAUSE_LIGHT);
        }
        return changed;
    }

    /**
     * {@code getChunkDepthData} returns a reused thread-local, so each depth is read into a local
     * before the next call.
     */
    private static boolean shiftDepth(
            PuddleBatch batch, IsoChunk chunk, int z, int camChunkX, int camChunkY) {
        float now =
                IsoDepthHelper.getChunkDepthData(camChunkX, camChunkY, chunk.wx, chunk.wy, z)
                        .depthStart;
        float before =
                IsoDepthHelper.getChunkDepthData(
                                batch.camChunkX, batch.camChunkY, chunk.wx, chunk.wy, z)
                        .depthStart;
        if (batch.shiftDepth(now - before, camChunkX, camChunkY)) {
            batch.markDirty(PuddleBatch.CAUSE_SHIFT);
            return true;
        }
        return false;
    }

    private static void checkSettings(IsoCell cell, boolean noLighting) {
        if (lastCell.get() != cell) {
            lastCell = new WeakReference<>(cell);
            if (!TABLE.isEmpty()) {
                PuddleBatchStats.worldDrops += TABLE.dropAll(RETIRED);
            }
        }
        int quality = PerformanceSettings.puddlesQuality;
        int perfPuddles = Core.getInstance().getPerfPuddles();
        if (quality != lastQuality
                || perfPuddles != lastPerfPuddles
                || noLighting != lastNoLighting) {
            lastQuality = quality;
            lastPerfPuddles = perfPuddles;
            lastNoLighting = noLighting;
            if (!TABLE.isEmpty()) {
                TABLE.invalidateAll();
                PuddleBatchStats.settingsInvalidations++;
            }
        }
    }

    private static void sweep(int frame) {
        int sinceSweep = frame - lastSweepFrame;
        if (sinceSweep >= 0 && sinceSweep < SWEEP_INTERVAL_FRAMES) {
            return;
        }
        lastSweepFrame = frame;
        if (!TABLE.isEmpty()) {
            PuddleBatchStats.swept += TABLE.sweep(frame, SWEEP_MAX_AGE_FRAMES, RETIRED);
        }
    }

    /** Buffers are deleted on the render thread, so an empty command carries the request. */
    private static void drainRetired() {
        if (!RETIRED.isEmpty()) {
            SpriteRenderer.instance.drawGeneric(PuddleBatchPools.allocCommand().init(0, 0, 0, 0));
        }
    }

    private static void releaseEverything() {
        try {
            TABLE.dropAll(RETIRED);
            drainRetired();
        } catch (Throwable t) {
            StormLogger.LOGGER.error("[PuddleBatch] could not release the batch table", t);
        }
    }

    private static void discardCommands() {
        for (int z = 0; z < COMMANDS.length; z++) {
            PuddleBatchDrawCommand command = COMMANDS[z];
            if (command != null) {
                COMMANDS[z] = null;
                command.release();
            }
        }
    }

    /** Callable from any thread. Logs the first failure and latches the batch path off. */
    static void fail(String where, Throwable t) {
        if (failed) {
            return;
        }
        failed = true;
        StormLogger.LOGGER.error(
                "[PuddleBatch] " + where + " failed; vanilla puddle rendering is used from now on",
                t);
    }

    static boolean hasFailed() {
        return failed;
    }

    /** True while hooks have anything to mark. */
    static boolean isActive() {
        return !failed && !TABLE.isEmpty();
    }

    /** Runtime switch for A/B runs through eval. The next pass drops the table when turned off. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled && !failed;
    }

    public static String stats() {
        return "PuddleBatch[enabled="
                + enabled
                + " failed="
                + failed
                + " interval="
                + INTERVAL
                + " chunks="
                + TABLE.chunkCount()
                + " "
                + PuddleBatchStats.describe()
                + "]";
    }
}
