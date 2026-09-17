package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Runs the Lua/script/anim checksum exchange over the game-port TCP channel when the
 * client holds a Storm session. Vanilla's exchange is up to dozens of UDP round trips gated on a
 * loader-thread spin loop with a fixed timeout; one lost datagram costs the whole timeout.
 *
 * <p>Why a client bytecode patch: the exchange starts from {@code
 * NetChecksum.Comparer.beginCompare} on the loader thread before Lua is loaded, and the packet
 * writes are private. Fail-soft: the helper resets the comparer and returns {@code false} on any
 * failure, and the vanilla UDP exchange runs from the start.
 */
public class ChecksumOverTcpPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.checksumovertcp.BeginCompareAdvice";

    public ChecksumOverTcpPatch() {
        super("zombie.network.NetChecksum$Comparer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("beginCompare")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
