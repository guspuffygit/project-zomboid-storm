package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Marks the persistence routes into {@code IsoChunk.SaveLoadedChunk} so the chunk-streaming
 * serialize metrics can exclude them. The same advice is woven onto two methods.
 *
 * <p>{@code ServerChunkLoader.addSaveLoadedJob} delegates to {@code SaveChunkThread.addLoadedJob},
 * which serializes a still-loaded chunk synchronously on the calling thread before queueing the
 * result. Its two call sites are both in {@code ServerCell.Save}, reached from {@code SaveAll} on
 * the main thread and from a {@code ServerMap} worker thread.
 *
 * <p>{@code ServerChunkLoader$SaveUnloadedTask.serialize} serializes an unloading chunk. {@code
 * save()} calls it on the save thread, and {@code copyChunkData()} calls it on a LoadChunk worker
 * when a chunk is re-requested while its unload save is still pending. Both routes reach {@code
 * SaveLoadedChunk} only through {@code serialize}, which caches its result, so bracketing that one
 * method covers the unload path exactly once per chunk.
 *
 * <p>The remaining caller, {@code PlayerDownloadServer.update}, is never bracketed and stays
 * labelled {@code download}. Callers run on several threads, which is why the marker is a
 * thread-local depth rather than a static flag.
 *
 * <p>Deliberately not gated on {@code GameServer.server}: the enter and exit halves must always
 * pair, and a flag that flipped between them would leave the depth counter stuck above zero and
 * mislabel every later download serialize as a save.
 */
public class ServerChunkLoaderSaveLoadedJobAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        ChunkStreamMetrics.enterPersistenceSave();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        ChunkStreamMetrics.exitPersistenceSave();
    }
}
