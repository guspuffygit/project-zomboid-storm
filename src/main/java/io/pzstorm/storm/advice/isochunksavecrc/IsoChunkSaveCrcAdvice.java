package io.pzstorm.storm.advice.isochunksavecrc;

import java.util.zip.CRC32;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoChunk.Save(ByteBuffer, CRC32, boolean)}.
 *
 * <p>The hot-save path ({@code ServerChunkLoader$SaveChunkThread.addLoadedJob ->
 * IsoChunk.SaveLoadedChunk}) always hands this method the same {@code CRC32} instance &mdash; a
 * single field on {@code ServerChunkLoader$SaveChunkThread}, one object for the whole server.
 * {@code ServerMap.SaveAll} runs that hot-save path from up to 4 concurrent worker threads once 10
 * or more cells are loaded (one thread per cell, cells round-robined across the pool), so two
 * threads can call {@code reset()}/{@code update()} on that same {@code CRC32} at once. {@code
 * java.util.zip.CRC32} keeps mutable running-checksum state and is not thread-safe: an interleaved
 * {@code reset()} or {@code update()} from another thread corrupts the value a thread reads back
 * from {@code getValue()} for its own, otherwise-untouched buffer.
 *
 * <p>{@code Save} bakes that value into the chunk's on-disk header (length is written correctly,
 * only the CRC can be wrong). On the next cold load of that chunk &mdash; evicted then revisited,
 * or a full restart &mdash; {@code IsoChunk.LoadChunk} treats the header/payload CRC mismatch as an
 * unrecoverable file and silently replaces the chunk with a freshly world-generated one, destroying
 * every player-built structure and item that chunk had, with no visible warning.
 *
 * <p>Fix: give every call into this method its own {@code CRC32} on entry, so no two callers can
 * ever share mutable checksum state regardless of what instance was passed in. Allocation cost is a
 * few bytes per call, negligible next to the serialization work {@code Save} already does.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class IsoChunkSaveCrcAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 1, readOnly = false) CRC32 crc) {
        crc = new CRC32();
    }
}
