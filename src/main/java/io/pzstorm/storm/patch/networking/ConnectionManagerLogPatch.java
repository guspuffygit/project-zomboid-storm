package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.network.IConnection;

/**
 * Turns PZ's connections log into Prometheus counters, so the login funnel is visible as a rate
 * instead of only as text nobody reads during an incident.
 *
 * <p>{@code ConnectionManager.log} is the single choke point every connection-lifecycle event in
 * the game already passes through — RakNet accepts and drops, each handshake packet in both
 * directions, the spawn itself — with a bounded pair of label strings supplied at each call site.
 * Counting there covers ~27 event types for one patch, instead of one patch per packet handler.
 *
 * <p>Both overloads are hooked, but the {@code IConnection} one skips its null-connection path
 * because that path delegates to the {@code long} overload; without the skip every null-connection
 * event would be counted twice.
 *
 * <p>{@code ConnectionManager} also runs on the client (it drives client-side connect requests), so
 * this patch relies on {@code StormEnv.isStormServer()} registration gating — it is never installed
 * in a client JVM — plus the runtime {@code GameServer.server} check in both advice bodies.
 *
 * @see io.pzstorm.storm.metrics.StormConnectionEventMetrics
 */
public class ConnectionManagerLogPatch extends StormClassTransformer {

    private static final String GUID_ADVICE =
            "io.pzstorm.storm.advice.connectionevents.ConnectionManagerLogGuidAdvice";

    private static final String CONNECTION_ADVICE =
            "io.pzstorm.storm.advice.connectionevents.ConnectionManagerLogConnectionAdvice";

    public ConnectionManagerLogPatch() {
        super("zombie.network.ConnectionManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(typePool.describe(GUID_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("log")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class,
                                                                String.class,
                                                                long.class))))
                .visit(
                        Advice.to(typePool.describe(CONNECTION_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("log")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class,
                                                                String.class,
                                                                IConnection.class))));
    }
}
