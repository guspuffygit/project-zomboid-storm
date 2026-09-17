package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server only. Drops a second LoginPacket on a connection that already logged in. Vanilla's {@code
 * processServer} re-stamps the connection, re-authenticates and pushes the connection details again
 * on every Login it sees; a Storm client whose TCP login post succeeded server-side but whose
 * response was lost falls back to a UDP Login, and without this guard that fallback would run the
 * handler twice. Vanilla clients send one Login per connection, so the guard never fires for them.
 */
public class LoginPacketDuplicateGuardPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.loginduplicateguard.LoginPacketProcessServerAdvice";

    public LoginPacketDuplicateGuardPatch() {
        super("zombie.network.packets.connection.LoginPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("processServer")
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
