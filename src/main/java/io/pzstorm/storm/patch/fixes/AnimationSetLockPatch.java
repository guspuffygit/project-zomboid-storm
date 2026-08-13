package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.advice.animsetlock.AnimSetLockAdvice;
import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Serializes {@code AnimationSet.GetAnimationSet} and {@code AnimationSet.Reset} on {@link
 * AnimSetLockAdvice#LOCK}.
 *
 * <p>{@code GetAnimationSet} is get-then-load-then-put on the static {@code setMap} {@code
 * HashMap}, and {@code Load} feeds {@code AnimNodeAssetManager}'s {@code THashMap} through {@code
 * AnimState.Parse}. The SPVThread reaches this path through {@code IsoAnimal.init} while loading
 * animal-carrying vehicles; the main thread reaches it during the boot-time {@code
 * refreshAnimSets(true)} pass and on every character creation. Concurrent puts corrupted the THash
 * internals ({@code ArrayIndexOutOfBoundsException} in {@code THashMap.rehash}), and the
 * vehicle-load error handler deleted the vehicle from vehicles.db in response.
 *
 * <p>Only creation/load paths call these methods (never per-tick), so the lock adds no steady-state
 * main-thread cost. Server-only; see {@link AnimSetLockAdvice} for the race and lock-ordering
 * analysis, and {@link RefreshAnimSetsLockPatch} for the third participant.
 */
public class AnimationSetLockPatch extends StormClassTransformer {

    public AnimationSetLockPatch() {
        super("zombie.core.skinnedmodel.advancedanimation.AnimationSet");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(AnimSetLockAdvice.class)
                        .on(
                                ElementMatchers.namedOneOf("GetAnimationSet", "Reset")
                                        .and(ElementMatchers.isStatic())));
    }
}
