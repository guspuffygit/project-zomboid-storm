package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.network.chat.ChatType;

/**
 * Triggered when ChatManager.sendMessageToChat is called. Can be cancelled to prevent the message
 * from being sent.
 */
public class OnSendMessageToChatEvent implements LuaEvent {

    public final String author;
    public final ChatType chatType;
    public final String message;
    public boolean isCancelled = false;

    public OnSendMessageToChatEvent(String author, ChatType chatType, String message) {
        this.author = author;
        this.chatType = chatType;
        this.message = message;
    }

    /** Call this to prevent the original sendMessageToChat method from executing. */
    public void cancel() {
        this.isCancelled = true;
    }
}
