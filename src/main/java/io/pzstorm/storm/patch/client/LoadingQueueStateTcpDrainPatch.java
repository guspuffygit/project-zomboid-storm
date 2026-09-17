package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Applies login-queue messages that arrived over TCP at the top of {@code
 * LoadingQueueState.update()}, the main-thread tick that vanilla uses to apply the UDP ones, so
 * {@code LoadingQueueState}'s private {@code done} flag and the queue UI are touched from one
 * thread only.
 *
 * <p>Why a client bytecode patch: the state's flags are private statics with no event fired while
 * it waits. Fail-soft: the advice suppresses every throwable, and with no TCP messages pending it
 * is a no-op.
 */
public class LoadingQueueStateTcpDrainPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.loginqueueovertcp.LoadingQueueStateUpdateAdvice";

    public LoadingQueueStateTcpDrainPatch() {
        super("zombie.gameStates.LoadingQueueState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
