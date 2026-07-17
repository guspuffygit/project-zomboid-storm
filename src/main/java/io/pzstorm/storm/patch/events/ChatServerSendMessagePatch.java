package io.pzstorm.storm.patch.events;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only patch on {@code ChatServer.sendMessage(ChatMessage)} that dispatches {@link
 * io.pzstorm.storm.event.zomboid.OnServerChatMessageEvent} for every chat message the server
 * relays, so server-side code can observe chat without any client involvement. The client-side
 * {@code OnAddMessage} Lua event never fires in the dedicated-server JVM; this is the server
 * equivalent.
 */
public class ChatServerSendMessagePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.chatserver.";

    public ChatServerSendMessagePatch() {
        super("zombie.network.chat.ChatServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "ChatServerSendMessageAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("sendMessage")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
