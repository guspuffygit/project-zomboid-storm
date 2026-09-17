package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Diverts the loading-phase bulk transfers (ZombieOutfitDescriptors,
 * PlayerZombieDescriptors, RadioData, WorldMap) from the UDP part-transfer machinery (1&nbsp;KB
 * packets, 200&nbsp;KB ACK windows, an unbounded wait loop with no timeout) to the Storm game-port
 * TCP channel, when the client established a handshake with a Storm server.
 *
 * <p>Why a client bytecode patch: the chain is driven by a Java spin loop on the loader thread
 * ({@code GameClient.GameLoadingRequestData}) before any Lua event fires for this phase, and no
 * existing Storm surface intercepts it. Fail-soft: the advice helper returns {@code false} on any
 * problem — no session, server without the game-port endpoint, download failure — and the vanilla
 * UDP chain runs exactly as before; a broken patch degrades to vanilla behavior, never a stuck
 * client.
 */
public class RequestDataOverTcpPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.requestdataovertcp.";

    public RequestDataOverTcpPatch() {
        super("zombie.network.GameClient");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "GameLoadingRequestDataAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("GameLoadingRequestData")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
