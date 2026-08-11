package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Measures the one global monitor that couples every player's chunk streaming together.
 *
 * <p>{@code ChunkChecksum.getChecksum} synchronises its whole body on a static cache and, on a
 * miss, reads the chunk file through shared static {@code CRC32} and buffer instances. Every {@code
 * PlayerDownloadServer} worker on the server calls it once per disk-served chunk, so held time here
 * is time subtracted from every other peer's dispatch slot at once.
 *
 * <p>This measurement is what would justify the fix — per-key locking or a concurrent cache — so it
 * has to come first. Pure measurement.
 */
public class ChunkChecksumMetricsPatch extends StormClassTransformer {

    private static final String ADVICE = "io.pzstorm.storm.advice.chunkstream.GetChecksumAdvice";

    public ChunkChecksumMetricsPatch() {
        super("zombie.network.ChunkChecksum");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("getChecksum")
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
