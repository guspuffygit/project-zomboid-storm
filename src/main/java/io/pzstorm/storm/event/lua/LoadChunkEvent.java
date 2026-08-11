package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoChunk;

/**
 * Triggered on the main thread once per {@link IsoChunk} inserted into the world, at the end of
 * {@code IsoChunk.doLoadGridsquare()}.
 *
 * <p>This is the only exact chunk-arrival signal available without a bytecode patch: vanilla keeps
 * no counter of chunks hydrated, and the {@code WorldStreamer.ChunkRequest} that carried the chunk
 * is pooled and released on the same World Streamer pass that completes it, so a periodic sampler
 * can only ever see requests still in flight. Counting this event gives the client-side delivery
 * rate to pair against the server's {@code storm_chunk_stream_sent_total}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class LoadChunkEvent implements LuaEvent {

    /** {@link IsoChunk} that has just been inserted into the world. */
    public final IsoChunk chunk;

    public LoadChunkEvent(IsoChunk chunk) {
        this.chunk = chunk;
    }
}
