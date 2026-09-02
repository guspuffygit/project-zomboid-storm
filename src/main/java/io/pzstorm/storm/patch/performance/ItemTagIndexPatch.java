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
 * Adds {@code stormIndex} (public {@code int}) to {@code zombie.scripting.objects.ItemTag},
 * assigned from {@code StormItemTagIndex} at the end of the constructor, and implements {@link
 * io.pzstorm.storm.entity.StormItemTagIndexHolder} over it. Pairs with {@link ItemTagMaskPatch}.
 * Server-only by registration gate.
 */
public class ItemTagIndexPatch extends StormClassTransformer {

    public ItemTagIndexPatch() {
        super("zombie.scripting.objects.ItemTag");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("stormIndex", int.class, Visibility.PUBLIC)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormItemTagIndexHolder")
                                .resolve())
                .intercept(FieldAccessor.ofField("stormIndex"))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        "io.pzstorm.storm.advice.itemtag"
                                                                + ".ItemTagIndexAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.isConstructor()));
    }
}
