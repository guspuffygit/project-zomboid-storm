package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Serves the Storm Launcher's pre-login server query from {@code GameServer.addIncoming}.
 *
 * <p>The advice references {@code GameServer}, which is also the transform target, so it must be
 * resolved through the type pool rather than {@code Advice.to(Class)} — the reflective form would
 * fail with {@code ClassCircularityError} at patch init.
 */
public class ServerQueryPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serverquery.";

    public ServerQueryPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ServerQueryAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("addIncoming")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
