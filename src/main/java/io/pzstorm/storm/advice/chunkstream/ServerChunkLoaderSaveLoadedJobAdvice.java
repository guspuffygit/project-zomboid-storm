package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Marks the persistence route into {@code IsoChunk.SaveLoadedChunk} so the chunk-streaming
 * serialize metrics can exclude it.
 *
 * <p>{@code addSaveLoadedJob} delegates to {@code SaveChunkThread.addLoadedJob}, which serializes
 * the chunk synchronously on the calling thread before queueing the result — so wrapping this
 * method brackets exactly the {@code SaveLoadedChunk} calls that belong to saving, and none of the
 * ones {@code PlayerDownloadServer.update} makes. Its two call sites are both in {@code
 * ServerCell.Save}, reached from {@code SaveAll} on the main thread and from a {@code ServerMap}
 * worker thread, which is why the marker has to be thread-local rather than a static flag.
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
