package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.raknet.UdpConnection;

/**
 * Frees RakNet slots held by connections that stalled mid-handshake, so a busy server does not
 * exhaust its (hardcoded, 101-entry) incoming-connection pool and leave every new joiner stuck on
 * "Getting Server Info...".
 *
 * <p>Two hooks, both inside {@code GameServer}:
 *
 * <ul>
 *   <li>{@code addIncoming} — records last-inbound-packet time per connection. {@link
 *       UdpConnection} has no per-packet activity timestamp of its own ({@code lastConnection}
 *       holds the <i>previous</i> session's login time), so the reaper keeps its own.
 *   <li>{@code launchCommandHandler} — per-tick main-thread entry point for the sweep.
 * </ul>
 *
 * <p>Vanilla's own reap in {@code GameServer.main} is left untouched; it only ever fires for
 * connections that never sent a username. See {@link
 * io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper} for the reap rules.
 */
public class GameServerStalledConnectionReapPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.gameserverstalledconnections.";

    public GameServerStalledConnectionReapPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "GameServerAddIncomingAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("addIncoming")
                                                .and(ElementMatchers.takesArguments(3))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "GameServerReapSweepAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("launchCommandHandler")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
