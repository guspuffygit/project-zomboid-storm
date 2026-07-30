package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Frees RakNet slots held by connections that never finish logging in, so a busy server does not
 * exhaust its (hardcoded, 101-entry) incoming-connection pool and leave every new joiner stuck on
 * "Getting Server Info...".
 *
 * <p>Single hook: exit advice on {@code GameServer.launchCommandHandler}, which the server
 * frame-step block calls exactly once per tick on the main thread. The reaper is wall-clock based
 * (time since a sweep first saw the connection), so no per-packet activity hook is needed — see
 * {@link io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper} for the reap
 * rules and exemptions.
 *
 * <p>Vanilla's own reap in {@code GameServer.main} is left untouched; it only ever fires for
 * connections that never sent a username.
 */
public class GameServerStalledConnectionReapPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.gameserverstalledconnections.GameServerReapSweepAdvice";

    public GameServerStalledConnectionReapPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("launchCommandHandler")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
