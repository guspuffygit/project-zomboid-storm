package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the server postupdate-loop crash triggered when an {@code IsoAnimal} reaches {@code
 * IsoMovingObject.postupdate} -&gt; {@code doStairs} with {@code adef == null}:
 *
 * <pre>NullPointerException: Cannot read field "canClimbStairs" because "...adef" is null
 *     at IsoAnimal.canClimbStairs(IsoAnimal.java:3621)
 *     at IsoMovingObject.doStairs(IsoMovingObject.java:283)
 *     at IsoMovingObject.postupdate(IsoMovingObject.java:1011)</pre>
 *
 * <p>This is the {@code postupdate} sibling of {@link IsoAnimalUpdateNullDefGuardPatch}: the
 * existing {@code update()} guard queues a null-{@code adef} animal for removal at tick end, but
 * the same tick's {@code postupdate} pass still runs first, invoking {@link
 * zombie.iso.IsoMovingObject#doStairs()}. That method unconditionally calls {@code
 * ((IsoAnimal)this).canClimbStairs()} when {@code this} is an animal, and the unguarded {@code
 * return this.adef.canClimbStairs} aborts the entire postupdate iteration via uncaught NPE; the
 * catch sits all the way up at {@code IngameState.updateInternal}, so the world stops advancing.
 *
 * <p>The patch installs an {@code @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)} that, on
 * the server only, returns {@code true} when {@code adef} is null. Skipping the body causes the
 * boolean method to return {@code false} (its default), which is the safe "can't climb" answer.
 * Healthy animals fall through and the original body runs.
 */
public class IsoAnimalCanClimbStairsNullDefGuardPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalcanclimbstairsnulldefguard.";

    public IsoAnimalCanClimbStairsNullDefGuardPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoAnimalCanClimbStairsNullDefGuardAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("canClimbStairs")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
