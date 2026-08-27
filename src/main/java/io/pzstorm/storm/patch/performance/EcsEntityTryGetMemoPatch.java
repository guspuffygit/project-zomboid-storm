package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Weaves the memo fast path into the {@code ECSEntity.tryGetECSComponent(Class)} default method
 * (the interface owns the bytecode — no class overrides it, verified against 42.20.4 source).
 * {@code EcsGetClassCachePatch} already caches the static {@code getECSClass} superclass walk; this
 * patch removes the remaining per-call {@code HashMap} probe for characters via the per-instance
 * memo installed by {@link IsoGameCharacterEcsMemoPatch} — together the {@code getOwner}/{@code
 * getOwnerPlayer}/{@code getStateMachineComponent}/{@code getFrameKeeper} chains were ~1.5% of
 * server main (ATF profile 2026-08-26, 135 players).
 *
 * <p>Pair with {@link IsoGameCharacterEcsMemoPatch}; without it every entity fails the {@code
 * instanceof} guard and the advice is a no-op. Server-only by registration gate ({@code
 * StormEnv.isStormServer()}).
 */
public class EcsEntityTryGetMemoPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.ecs.ECSEntity";
    private static final String PKG = "io.pzstorm.storm.advice.ecsmemo.";

    public EcsEntityTryGetMemoPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("tryGetECSComponent")
                                .and(ElementMatchers.takesArguments(1))
                                .and(ElementMatchers.isDefaultMethod()))
                .isEmpty()) {
            throw new IllegalStateException(
                    "EcsEntityTryGetMemoPatch: ECSEntity no longer declares a default"
                            + " tryGetECSComponent(Class) — the memo advice would silently not"
                            + " weave (or an implementor override would bypass it). Re-verify"
                            + " against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "EcsTryGetComponentMemoAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("tryGetECSComponent")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
