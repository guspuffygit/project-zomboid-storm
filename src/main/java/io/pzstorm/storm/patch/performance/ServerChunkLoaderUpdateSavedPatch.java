package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Also carries the two hydration-stage boundaries. {@code addJob} and {@code addRecalcJob} are the
 * only entry points to the LoadChunk and RecalcAll threads and have one call site each in {@code
 * ServerMap.preupdate}, which makes them exact stage markers for {@code
 * storm_chunk_hydration_cell_duration_seconds}. They ride this transformer because it already owns
 * {@code ServerChunkLoader}.
 */
public class ServerChunkLoaderUpdateSavedPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serverchunkloaderupdatesaved.";

    private static final String HYDRATION_PKG = "io.pzstorm.storm.advice.chunkhydration.";

    private static final String STREAM_PKG = "io.pzstorm.storm.advice.chunkstream.";

    public ServerChunkLoaderUpdateSavedPatch() {
        super("zombie.network.ServerChunkLoader");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG + "ServerChunkLoaderUpdateSavedAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("updateSaved")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        HYDRATION_PKG
                                                                + "ServerChunkLoaderAddJobAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("addJob")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        HYDRATION_PKG
                                                                + "ServerChunkLoaderAddRecalcJobAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("addRecalcJob")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        STREAM_PKG
                                                                + "ServerChunkLoaderSaveLoadedJobAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("addSaveLoadedJob")
                                                .and(ElementMatchers.takesArguments(1))));
    }
}
