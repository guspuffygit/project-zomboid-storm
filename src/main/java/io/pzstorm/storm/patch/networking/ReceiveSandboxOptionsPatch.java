package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@code GameServer.receiveSandboxOptions(ByteBufferReader, UdpConnection, short)} so that
 * Storm re-reads sandbox options after every admin-UI push. Vanilla updates {@code
 * SandboxOptions.instance} in place and fires no Lua event, so without this hook Storm's
 * controllers (and the Prometheus gauges that mirror them) stay frozen at whatever was active when
 * {@code OnServerStartedEvent} fired.
 *
 * <p>Server-only by registration in {@code StormClassTransformers}.
 */
public class ReceiveSandboxOptionsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.receivesandboxoptions.";

    public ReceiveSandboxOptionsPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ReceiveSandboxOptionsAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("receiveSandboxOptions")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
