package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Constructor-exit hook on {@code zombie.entity.EntityBucket} that registers each bucket's entity
 * array with {@code StormEntityIndex}. Extends the O(1) entity-removal fast path from the global
 * array to the per-bucket arrays: {@code EngineEntityManager.removeEntityInternal} calls {@code
 * EntityBucketManager.updateBucketMembership}, which runs {@code entities.removeValue(entity,
 * true)} in every bucket the entity is leaving — at ATF scale those bucket scans were ~2% of
 * main-thread time (415/418 remaining {@code Array.removeValue} JFR samples after the global-array
 * index shipped).
 *
 * <p>The advice fires on the private {@code EntityBucket(int)} constructor, covering both the
 * lazily-created buckets ({@code getIsoObjectBucket} etc.) and the renderer bucket constructed
 * inside {@code EngineEntityManager}'s own constructor.
 *
 * <p>Guard: verifies {@code EntityBucket} still declares an {@code entities} field — {@code
 * StormEntityIndex.onBucketCreated} reflects it, and a rename would silently leave every bucket
 * untracked.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — the class also runs on
 * client JVMs (HARD RULE: nothing weaves client-side).
 */
public class EntityBucketIndexPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.entity.EntityBucket";
    private static final String PKG = "io.pzstorm.storm.advice.entityindex.";

    public EntityBucketIndexPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredFields().filter(ElementMatchers.named("entities")).isEmpty()) {
            throw new IllegalStateException(
                    "EntityBucketIndexPatch: EntityBucket no longer declares an 'entities' field —"
                            + " StormEntityIndex.onBucketCreated reflects it and every bucket"
                            + " would silently stay untracked. Re-verify against the current game"
                            + " source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "EntityBucketCreatedAdvice").resolve(), locator)
                        .on(ElementMatchers.isConstructor()));
    }
}
