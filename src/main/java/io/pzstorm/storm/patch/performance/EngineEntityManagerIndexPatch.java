package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Constructor-exit hook on {@code zombie.entity.EngineEntityManager} that registers each new
 * manager's global entity array with {@code StormEntityIndex}. One half of the O(1) entity-removal
 * fast path; the other half is {@code EntityArrayRemoveFastPathPatch} on {@code
 * zombie.entity.util.Array}.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — the class also runs on
 * client JVMs (HARD RULE: nothing weaves client-side).
 */
public class EngineEntityManagerIndexPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.entityindex.";

    public EngineEntityManagerIndexPatch() {
        super("zombie.entity.EngineEntityManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "EngineEntityManagerCreatedAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.isConstructor()));
    }
}
