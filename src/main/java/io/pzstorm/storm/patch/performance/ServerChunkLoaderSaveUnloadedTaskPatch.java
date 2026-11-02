package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Marks {@code ServerChunkLoader$SaveUnloadedTask.serialize()} as a persistence save so {@code
 * IsoChunk.SaveLoadedChunk} calls made from it are labelled {@code caller="save"} rather than
 * {@code "download"}. Both {@code save()} on the save thread and {@code copyChunkData()} on a
 * LoadChunk worker reach {@code SaveLoadedChunk} only through {@code serialize()}, so this one
 * method is the whole unload-save route. Registered next to {@link
 * ServerChunkLoaderUpdateSavedPatch}, which brackets the loaded-chunk route on the outer class.
 */
public class ServerChunkLoaderSaveUnloadedTaskPatch extends StormClassTransformer {

    private static final String STREAM_PKG = "io.pzstorm.storm.advice.chunkstream.";

    public ServerChunkLoaderSaveUnloadedTaskPatch() {
        super("zombie.network.ServerChunkLoader$SaveUnloadedTask");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                STREAM_PKG + "ServerChunkLoaderSaveLoadedJobAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("serialize")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
