package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a latent NPE in the server physics-separation pass when an {@code IsoAnimal} with {@code
 * adef == null} participates in collision separation:
 *
 * <pre>NullPointerException: Cannot read field "collidable" because "...adef" is null
 *     at IsoMovingObject.isPushedByForSeparate(IsoMovingObject.java:1129)</pre>
 *
 * <p>Vanilla {@code isPushedByForSeparate} unguardedly dereferences {@code
 * ((IsoAnimal)this).adef.collidable} and {@code isoAnimal.adef.collidable} on both sides of the
 * pair. The {@link IsoAnimalUpdateNullDefGuardPatch} only queues a broken animal for tick-end
 * removal, so until the drain runs the animal can still be picked up by the separation pass.
 *
 * <p>The patch installs an {@code @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)} that, on
 * the server only, returns {@code true} when either {@code this} or {@code other} is an {@code
 * IsoAnimal} with null {@code adef}. Skipping the body causes the boolean method to return {@code
 * false}, which is identical to vanilla's "non-collidable or block-movement" branch and a safe
 * answer for an animal that's about to be removed.
 */
public class IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch extends StormClassTransformer {

    private static final String PKG =
            "io.pzstorm.storm.advice.movingobjectispushedbyforseparatenulldefguard.";

    public IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch() {
        super("zombie.iso.IsoMovingObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                PKG
                                                        + "IsoMovingObjectIsPushedByForSeparateNullDefGuardAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("isPushedByForSeparate")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
