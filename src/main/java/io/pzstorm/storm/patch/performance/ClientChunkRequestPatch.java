package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

/**
 * Defines {@code storm$enqueueNanos}, the per-request stamp behind {@code
 * storm_chunk_stream_queue_wait_seconds}. It lives here because this is the transformer that owns
 * {@code ClientChunkRequest}; the stamp itself is written by {@link
 * io.pzstorm.storm.advice.chunkstream.ClientChunkRequestEnqueueAdvice} over on {@code
 * PlayerDownloadServer}, which is why it cannot be a plain {@code @Advice.FieldValue}. Vanilla has
 * no spare slot to reuse — {@code minX/maxX/minY/maxY} are dead in 42.20.x but are still vanilla
 * state a future build could revive.
 *
 * <p>Until 42.20.2 this transformer also counted the 3-strike chunk retry ladder; 42.20.3 removed
 * {@code getRetryChunk} entirely (missing chunks are now parked via {@code
 * PlayerDownloadServer.queueUntilGenerated} until world generation produces them).
 *
 * <p>Pure measurement.
 */
public class ClientChunkRequestPatch extends StormClassTransformer {

    public ClientChunkRequestPatch() {
        super("zombie.network.ClientChunkRequest");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$enqueueNanos", long.class, Visibility.PUBLIC);
    }
}
