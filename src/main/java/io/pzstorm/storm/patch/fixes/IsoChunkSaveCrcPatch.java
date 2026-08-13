package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a data-corruption bug where a chunk's on-disk save file ends up with a CRC that doesn't
 * match its own payload, which later makes {@code IsoChunk.LoadChunk} discard the chunk entirely
 * and regenerate it from scratch &mdash; silently destroying every player-built structure and item
 * in it.
 *
 * <p>Root cause: {@code IsoChunk.Save(ByteBuffer, CRC32, boolean)} computes the header CRC using
 * whatever {@code CRC32} instance the caller passes in. On the hot-save path ({@code
 * ServerChunkLoader$SaveChunkThread.addLoadedJob}) that's always the same shared instance, and
 * {@code ServerMap.SaveAll} drives that path from up to 4 concurrent worker threads once 10+ cells
 * are loaded. {@code CRC32} is stateful and not thread-safe, so concurrent {@code reset()}/{@code
 * update()} calls from two worker threads can corrupt the value either one reads back &mdash; the
 * buffer itself (and its length) stay correct, only the embedded CRC is wrong.
 *
 * <p>This patch makes {@code Save} allocate its own {@code CRC32} on entry instead of trusting the
 * caller's, so the computation is always isolated to the current call no matter how many threads
 * are saving different chunks at once.
 *
 * <p>Advice is loaded via {@code typePool.describe().resolve()} so Byte Buddy parses it via ASM
 * without triggering class loading of referenced game types.
 */
public class IsoChunkSaveCrcPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isochunksavecrc.";

    public IsoChunkSaveCrcPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoChunkSaveCrcAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("Save")
                                        .and(ElementMatchers.takesArguments(3))
                                        .and(ElementMatchers.takesArgument(0, ByteBuffer.class))));
    }
}
