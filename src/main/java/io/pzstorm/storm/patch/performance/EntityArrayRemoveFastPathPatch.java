package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments {@code zombie.entity.util.Array} so removals from the engine's entity arrays (the
 * global {@code EngineEntityManager.entities}, ~123k elements live at 79 players, and every {@code
 * EntityBucket.entities} re-scanned per removal by {@code updateBucketMembership}) resolve in O(1)
 * instead of a linear identity scan ({@code Array.removeValue} showed 3.1% main-thread self time
 * during cell-unload bursts pre-index; the bucket scans alone were ~2% at 116 players).
 *
 * <p>The redefinition adds a {@code stormEntityArrayIndex} field (public volatile {@code Object})
 * and implements {@link io.pzstorm.storm.entity.StormIndexedArray} with accessors over it, so each
 * tracked array carries its own removal index and discrimination is one field read (null =
 * untracked, fall through to vanilla).
 *
 * <p>Two advices, instance-gated inside {@code StormEntityIndex} through that field:
 *
 * <ul>
 *   <li>{@code EntityArrayAddAdvice} on the single-arg {@code add(T)} — index maintenance.
 *   <li>{@code EntityArrayRemoveValueAdvice} on {@code removeValue(T, boolean)} — indexed
 *       swap-with-last removal with an {@code items[index] == entity} self-check; on any
 *       inconsistency the fast path latches off permanently and vanilla scans resume.
 * </ul>
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code Array} is
 * ubiquitous on client JVMs too (HARD RULE: nothing weaves client-side).
 *
 * <p>Kill switch: the {@code Storm.EntityRemoveFastPath} sandbox option ({@code false} restores
 * vanilla scans; live-appliable — re-enabling triggers a one-off O(n) index rebuild).
 */
public class EntityArrayRemoveFastPathPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.entity.util.Array";
    private static final String PKG = "io.pzstorm.storm.advice.entityindex.";

    public EntityArrayRemoveFastPathPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                        .filter(ElementMatchers.named("add").and(ElementMatchers.takesArguments(1)))
                        .isEmpty()
                || target.getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("removeValue")
                                        .and(ElementMatchers.takesArguments(2)))
                        .isEmpty()) {
            throw new IllegalStateException(
                    "EntityArrayRemoveFastPathPatch: Array no longer declares add(T) and"
                            + " removeValue(T, boolean) — the name-string hooks would silently"
                            + " no-op (or worse, desync the entity index). Re-verify the patch"
                            + " against the current game source.");
        }
        return builder.defineField(
                        "stormEntityArrayIndex",
                        Object.class,
                        Visibility.PUBLIC,
                        FieldManifestation.VOLATILE)
                .implement(typePool.describe("io.pzstorm.storm.entity.StormIndexedArray").resolve())
                .intercept(FieldAccessor.ofField("stormEntityArrayIndex"))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "EntityArrayAddAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("add")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "EntityArrayRemoveValueAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("removeValue")
                                                .and(ElementMatchers.takesArguments(2))));
    }
}
