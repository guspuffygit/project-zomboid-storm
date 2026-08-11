package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Splits chunk disk-read time out of the download batch duration.
 *
 * <p>{@code storm_chunk_stream_batch_duration_seconds} says a peer's dispatch slot was held for N
 * ms but not by what. {@code SafeRead} is the largest serial component for any chunk not resident
 * in {@code ServerMap}, and its fair per-chunk read-write lock means the time can belong to another
 * thread's writer rather than to this read. Pure measurement.
 */
public class IsoChunkSafeReadPatch extends StormClassTransformer {

    private static final String ADVICE = "io.pzstorm.storm.advice.chunkstream.SafeReadAdvice";

    public IsoChunkSafeReadPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("SafeRead")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
