package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Wraps the {@code Set} returned by {@code IsoCell.getObjectList()} in an unmodifiable view while
 * the server is inside phase 4 of {@code IsoCell.updateInternal()} ({@code safeToAdd == false}), so
 * that any rogue mutation (Java or Lua-via-Kahlua) surfaces as an {@code
 * UnsupportedOperationException} at the call-site.
 *
 * <p>This is a pre-requisite canary for parallelizing {@code IsoPlayer.updateLOS} across worker
 * threads: structural stability of {@code objectList} during {@code ProcessObjects} is the safety
 * contract that the parallel iteration relies on. See {@code docs/LOS_OPTIMIZATION_FINDINGS.md}
 * §"Parallelization investigation" for context.
 */
public class IsoCellGetObjectListPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isocellobjectlist.";

    public IsoCellGetObjectListPatch() {
        super("zombie.iso.IsoCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoCellGetObjectListAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("getObjectList")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
