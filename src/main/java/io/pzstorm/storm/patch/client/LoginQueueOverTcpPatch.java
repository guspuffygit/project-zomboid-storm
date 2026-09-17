package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Sends the login-queue request and the loading-done notice over the game-port TCP
 * channel instead of the {@code LoginQueueRequest} / {@code LoginQueueDone} UDP packets, when the
 * client holds a Storm session. RakNet delivers both packets reliably, but neither side has an
 * application-level retry or deadline, so a drop above RakNet (the per-type send limiter, a parse
 * exception discarding the packet) stalls the client with no recovery. An HTTP request either
 * returns or times out, and the caller sees which.
 *
 * <p>Why a client bytecode patch: both sends are plain Java calls from the loading state machine,
 * before the Lua VM exists, with no Storm surface in between. Fail-soft: each advice helper returns
 * {@code false} on any problem and the vanilla UDP packet goes out as before.
 */
public class LoginQueueOverTcpPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.loginqueueovertcp.";

    public LoginQueueOverTcpPatch() {
        super("zombie.network.GameClient");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "SendLoginQueueRequestAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("sendLoginQueueRequest")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "SendLoginQueueDoneAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("sendLoginQueueDone")
                                                .and(ElementMatchers.takesArguments(long.class))));
    }
}
