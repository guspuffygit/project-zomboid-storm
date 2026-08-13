package io.pzstorm.storm.map;

import java.util.zip.CRC32;

/**
 * Per-thread replacement for the two shared {@link CRC32} instances raced by the chunk-save
 * pipeline: {@code ServerChunkLoader$SaveChunkThread.crc32} and {@code ServerChunkLoader.crcSave}.
 *
 * <p>{@code SaveChunkThread.addLoadedJob} serializes the chunk on the <b>calling</b> thread, and
 * during {@code ServerMap.SaveAll} with &ge;10 loaded cells four {@code WorkerThread}s call it
 * concurrently — all passing the one shared {@code crc32} into {@code IsoChunk.Save}, which ends
 * with reset/update/getValue and embeds the value in the chunk file header. Interleaving produces a
 * garbage embedded checksum, and every later load of that chunk logs "CRC mismatch" from {@code
 * sanityCheck.checkCRC} (log-only — the chunk bytes themselves are task-local and correct).
 *
 * <p>{@code SaveLoadedTask.save()} reads the outer {@code crcSave} and runs concurrently on the
 * SaveChunk thread (run loop) and the LoadChunk thread ({@code saveNow} before each chunk load).
 * Garbage there lands in the {@code ChunkChecksum} dedup cache — redundant disk writes and
 * redundant chunk resends to clients; a wrongly <i>skipped</i> write needs a 2^-32 collision.
 *
 * <p>{@code SaveChunkThreadCrcRacePatch} / {@code SaveLoadedTaskCrcRacePatch} redirect every read
 * of those fields to {@link #crc(Object)} via Byte Buddy {@code MemberSubstitution}, so each thread
 * gets its own instance. Every consumer sequence starts with {@code reset()} ({@code IsoChunk.Save}
 * resets before updating; {@code SaveLoadedTask.save} resets first), so carried-over state between
 * uses on the same thread is irrelevant, and the multiple reads within one {@code save()} call all
 * resolve to the same per-thread instance — the reset/update/getValue/getValue sequence stays
 * coherent. Single-threaded behaviour is unchanged: one thread sees one stable instance, exactly
 * like the original shared field.
 */
public final class StormChunkSaveCrc {

    private static final ThreadLocal<CRC32> CRC = ThreadLocal.withInitial(CRC32::new);

    private StormChunkSaveCrc() {}

    /**
     * Replacement for reads of {@code SaveChunkThread.crc32} and {@code ServerChunkLoader.crcSave}.
     * The owner instance the substituted {@code getfield} popped is ignored.
     */
    public static CRC32 crc(Object owner) {
        return CRC.get();
    }
}
