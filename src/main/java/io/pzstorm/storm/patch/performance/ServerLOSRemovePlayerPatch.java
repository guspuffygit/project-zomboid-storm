package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.IsoPlayer;

public class ServerLOSRemovePlayerPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serverlosfinddata.";

    public ServerLOSRemovePlayerPatch() {
        super("zombie.network.ServerLOS");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerLOSRemovePlayerAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("removePlayer")
                                        .and(ElementMatchers.takesArgument(0, IsoPlayer.class))));
    }
}
