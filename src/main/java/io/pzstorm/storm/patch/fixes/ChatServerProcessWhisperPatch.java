package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only patch that replaces {@code ChatServer.processPlayerStartWhisperChatPacket} with a
 * case-insensitive, canonical-username implementation. See {@link
 * io.pzstorm.storm.advice.whisperchatfix.ChatServerWhisperAdvice} for the failure modes this fixes.
 *
 * <p>{@code ChatServer} only runs in the dedicated-server JVM, so no client gate is needed.
 */
public class ChatServerProcessWhisperPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.whisperchatfix.";

    public ChatServerProcessWhisperPatch() {
        super("zombie.network.chat.ChatServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ChatServerWhisperAdvice").resolve(), locator)
                        .on(ElementMatchers.named("processPlayerStartWhisperChatPacket")));
    }
}
