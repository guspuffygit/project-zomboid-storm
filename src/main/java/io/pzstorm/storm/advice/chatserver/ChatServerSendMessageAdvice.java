package io.pzstorm.storm.advice.chatserver;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnServerChatMessageEvent;
import net.bytebuddy.asm.Advice;
import zombie.chat.ChatMessage;

/**
 * Advice for {@code ChatServer.sendMessage(ChatMessage)}. Dispatches an {@link
 * OnServerChatMessageEvent} after the message has been relayed to chat members.
 */
public class ChatServerSendMessageAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) ChatMessage message) {
        StormEventDispatcher.dispatchEvent(new OnServerChatMessageEvent(message));
    }
}
