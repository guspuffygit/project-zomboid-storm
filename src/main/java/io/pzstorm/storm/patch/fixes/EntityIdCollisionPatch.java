package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Stops {@code GameEntityManager.UnregisterEntity} throwing {@code Stored entity mismatch} when two
 * objects hold one entity id. See {@link EntityIdCollisionGuard}. Server only: the client branch of
 * that method has no comparison and cannot throw.
 */
public class EntityIdCollisionPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.entityidcollision.UnregisterEntityCollisionAdvice";

    public EntityIdCollisionPatch() {
        super("zombie.entity.GameEntityManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("UnregisterEntity")
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
