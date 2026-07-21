package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the server update-loop crash triggered when an {@code IsoAnimal} reaches its per-tick
 * {@code update()} with {@code adef == null}:
 *
 * <pre>NullPointerException: Cannot read field "turnDelta" because "...adef" is null
 *     at IsoAnimal.update(IsoAnimal.java:392)</pre>
 *
 * <p>The unguarded {@code this.setTurnDelta(this.adef.turnDelta)} call in {@code update()} aborts
 * the entire {@code MovingObjectUpdateSchedulerUpdateBucket.update} iteration via uncaught
 * exception; the catch sits all the way up at {@code IngameState.updateInternal}, so the offending
 * animal stays in the bucket and re-crashes every tick &mdash; the world stops progressing.
 *
 * <p>This patch installs an {@code @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)} that,
 * on the server only, attempts one re-resolve of {@code adef} via {@code
 * AnimalDefinitions.getDef(type)}; if the type still resolves to {@code null}, it immediately
 * detaches the animal from every per-tick collection ({@code objectList}, {@code addList}, {@code
 * removeList}, scheduler buckets, square moving-object lists) and returns {@code true} to skip the
 * original body. Healthy animals fall through with {@code false} and the original {@code update()}
 * runs.
 *
 * <p>Deferring the removal through {@code IsoCell.getRemoveList()} is not enough, and {@code
 * removeFromWorld()} cannot be used either &mdash; both fail for a half-loaded animal. See {@link
 * io.pzstorm.storm.advice.animalupdatenulldefguard.IsoAnimalUpdateNullDefGuardAdvice} for the full
 * failure chain, verified live against a production server stuck in this loop.
 */
public class IsoAnimalUpdateNullDefGuardPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalupdatenulldefguard.";

    public IsoAnimalUpdateNullDefGuardPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoAnimalUpdateNullDefGuardAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
