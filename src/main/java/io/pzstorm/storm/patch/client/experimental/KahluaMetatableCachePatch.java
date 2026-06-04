package io.pzstorm.storm.patch.client.experimental;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL — client-only.
 *
 * <p>{@code KahluaThread.getClassMetatable(Class)} caches per-class metatables via {@code
 * cachedMetatables.computeIfAbsent(c, lambda)}. The lambda allocates a fresh {@link
 * java.util.ArrayDeque} on every call to walk the class hierarchy. When the walk finds no metatable
 * the lambda returns {@code null}, but {@link java.util.Map} {@code .computeIfAbsent} does not
 * store {@code null} mappings — so the lambda re-fires on every subsequent op against the same
 * class. Client JFR profiling attributes ~85% of MainThread allocation pressure to this single
 * site.
 *
 * <p>This patch installs an exit advice that stores a singleton sentinel {@link
 * se.krka.kahlua.vm.KahluaTable} into the cache whenever the original method returns {@code null}.
 * Subsequent calls find the sentinel, skip the lambda, and the advice translates the sentinel back
 * to {@code null} for the caller. Cost: one extra allocation per unique no-metatable Class.
 */
public class KahluaMetatableCachePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.experimental.metatablecache.";

    public KahluaMetatableCachePatch() {
        super("se.krka.kahlua.vm.KahluaThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "KahluaMetatableCacheAdvice").resolve(), locator)
                        .on(ElementMatchers.named("getClassMetatable")));
    }
}
