package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class ServerCellLoad2Patch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.servercellload2.";

    public ServerCellLoad2Patch() {
        super("zombie.network.ServerMap$ServerCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerCellLoad2Advice").resolve(), locator)
                        .on(ElementMatchers.named("Load2").and(ElementMatchers.takesArguments(0))));
    }
}
