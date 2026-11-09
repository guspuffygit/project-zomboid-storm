package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.lazytiles=true}.
 *
 * <p>Starts page loads requested off the main thread and redraws chunk FBOs that skipped a tile.
 * See {@link io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class FileSystemLazyPageDrainPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public FileSystemLazyPageDrainPatch() {
        super("zombie.fileSystem.FileSystemImpl");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageDrainAdvice").resolve(), locator)
                        .on(ElementMatchers.named("updateAsyncTransactions")));
    }
}
