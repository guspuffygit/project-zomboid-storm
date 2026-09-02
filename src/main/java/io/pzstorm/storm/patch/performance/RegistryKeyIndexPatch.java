package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Adds {@code stormIndex} (public {@code int}) to a registry key class, assigned from {@link
 * io.pzstorm.storm.scripting.StormRegistryKeyIndex} at the end of every constructor, and implements
 * {@link io.pzstorm.storm.entity.StormIndexedKeyHolder} over it. Same shape as {@link
 * ItemTagIndexPatch}. Pairs with {@link IndexedMapFieldPatch}. Server-only by registration gate.
 */
public abstract class RegistryKeyIndexPatch extends StormClassTransformer {

    private final String adviceClass;

    protected RegistryKeyIndexPatch(String target, String adviceClass) {
        super(target);
        this.adviceClass = adviceClass;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("stormIndex", int.class, Visibility.PUBLIC)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormIndexedKeyHolder")
                                .resolve())
                .intercept(FieldAccessor.ofField("stormIndex"))
                .visit(
                        Advice.to(typePool.describe(adviceClass).resolve(), locator)
                                .on(ElementMatchers.isConstructor()));
    }
}
