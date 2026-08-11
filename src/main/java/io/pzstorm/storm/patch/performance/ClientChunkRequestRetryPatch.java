package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts the chunk-request retry ladder, which is the closest thing the server has to a direct
 * "player is outrunning world hydration" signal.
 *
 * <p>{@code getRetryChunk} is reached only from {@code PlayerDownloadServer.sendArray}, and only
 * for a chunk that is neither loaded in {@code ServerMap} nor present as a save file on disk. Three
 * strikes and the client is sent {@code NotRequiredInZip} with no data, leaving a hole that makes
 * {@code BaseVehicle.isInvalidChunkAhead} true and {@code CarController} force the brake.
 *
 * <p>Also defines {@code storm$enqueueNanos}, the per-request stamp behind {@code
 * storm_chunk_stream_queue_wait_seconds}. It lives here because this is the transformer that
 * already owns {@code ClientChunkRequest}; the stamp itself is written by {@link
 * io.pzstorm.storm.advice.chunkstream.ClientChunkRequestEnqueueAdvice} over on {@code
 * PlayerDownloadServer}, which is why it cannot be a plain {@code @Advice.FieldValue}. Vanilla has
 * no spare slot to reuse — {@code minX/maxX/minY/maxY} are dead in 42.20.2 but are still vanilla
 * state a future build could revive.
 *
 * <p>Pure measurement.
 */
public class ClientChunkRequestRetryPatch extends StormClassTransformer {

    private static final String ADVICE = "io.pzstorm.storm.advice.chunkstream.GetRetryChunkAdvice";

    public ClientChunkRequestRetryPatch() {
        super("zombie.network.ClientChunkRequest");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$enqueueNanos", long.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(typePool.describe(ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("getRetryChunk")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
