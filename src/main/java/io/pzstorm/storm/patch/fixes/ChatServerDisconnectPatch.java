package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces {@code ChatServer.disconnectPlayer(short)}, whose vanilla loop closes every whisper chat
 * server-wide on any player disconnect instead of only the leaver's. See {@link
 * io.pzstorm.storm.advice.whisperchatfix.ChatServerDisconnectAdvice}.
 *
 * <p>{@code ChatServer} only runs in the dedicated-server JVM, so no client gate is needed.
 */
public class ChatServerDisconnectPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.whisperchatfix.";

    public ChatServerDisconnectPatch() {
        super("zombie.network.chat.ChatServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ChatServerDisconnectAdvice").resolve(), locator)
                        .on(ElementMatchers.named("disconnectPlayer")));
    }
}
