package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Fetches the saved network-character profiles over the Storm game-port TCP channel
 * (one authenticated GET) instead of vanilla's up-to-4 sequential {@code LoadPlayerProfile} UDP
 * round trips polled at 50&nbsp;ms with a 10&nbsp;s timeout.
 *
 * <p>Why a client bytecode patch: the request/poll loop is loader-thread Java with no Lua event or
 * existing Storm surface in reach. Fail-soft by construction: the advice only pre-populates {@code
 * ClientPlayerDB.networkProfile}; the vanilla method afterward either takes its own fast path
 * (profile installed) or runs its normal UDP path (helper failed, nothing installed).
 */
public class PlayerProfileOverTcpPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.playerprofileovertcp.";

    public PlayerProfileOverTcpPatch() {
        super("zombie.savefile.ClientPlayerDB");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ClientLoadNetworkPlayerAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("clientLoadNetworkPlayer")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
