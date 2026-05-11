package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Phase 4 substitution patch — installs the authority/skip advice on {@code IsoPlayer.updateLOS}.
 * Registered before {@link IsoPlayerUpdateLOSPatch} so the existing timing advice still wraps the
 * substitute code path when this advice skips the original method body.
 */
public class IsoPlayerUpdateLOSAuthorityPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.playerlosauthority.";

    public IsoPlayerUpdateLOSAuthorityPatch() {
        super("zombie.characters.IsoPlayer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoPlayerUpdateLOSAuthorityAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateLOS")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
