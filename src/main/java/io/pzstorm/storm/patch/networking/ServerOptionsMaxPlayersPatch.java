package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Routes the return value of {@code ServerOptions.getMaxPlayers()} through {@code
 * StormMaxPlayersConfig} so the {@code Storm.OverrideMaxPlayers} / {@code Storm.MaxPlayers} sandbox
 * pair can replace the {@code .ini} value (and bypass vanilla's hard cap of 254) live. Registered
 * server-only; a vanilla or Storm client keeps the vanilla getter. See {@link
 * io.pzstorm.storm.advice.serveroptionsmaxplayers.ServerOptionsGetMaxPlayersAdvice}.
 */
public class ServerOptionsMaxPlayersPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.serveroptionsmaxplayers.";

    public ServerOptionsMaxPlayersPatch() {
        super("zombie.network.ServerOptions");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ServerOptionsGetMaxPlayersAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("getMaxPlayers")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(int.class))));
    }
}
