package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Retries the RakNet client socket when the random local port {@code
 * GameClient.startClient} drew is already in use, and surfaces a connect failure to the UI when it
 * cannot be recovered.
 *
 * <p>Why a client bytecode patch: {@code startClient} catches its own exception and returns
 * normally, {@code clientStarted} is private, and by then {@code ConnectionManager} has already
 * consumed the connect request, so neither Lua nor any existing Storm surface can see the failure
 * or retry it. Fail-soft: the advice runs after the vanilla body and does nothing at all unless
 * vanilla left the client unstarted.
 *
 * @see io.pzstorm.storm.client.StormClientSocketRetry
 */
public class GameClientStartClientRetryPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.clientsocketretry.StartClientAdvice";

    public GameClientStartClientRetryPatch() {
        super("zombie.network.GameClient");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("startClient")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
