package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;

public class ServerLOSIsCouldSeePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serverlosiscouldsee.";

    public ServerLOSIsCouldSeePatch() {
        super("zombie.network.ServerLOS");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerLOSIsCouldSeeAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("isCouldSee")
                                        .and(ElementMatchers.takesArgument(0, IsoPlayer.class))
                                        .and(
                                                ElementMatchers.takesArgument(
                                                        1, IsoGridSquare.class))));
    }
}
