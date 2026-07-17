package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import zombie.chat.ChatMessage;

/**
 * Dispatched on the dedicated server after {@code ChatServer.sendMessage(ChatMessage)} relays a
 * chat message to its chat members. This is the single point every player-authored message passes
 * through after word-filtering, regardless of chat type (general, say, shout, whisper, faction,
 * safehouse). Discord-relayed messages also pass through here — filter with {@link
 * ChatMessage#isFromDiscord()} / {@link ChatMessage#isServerAuthor()} if you only want players.
 *
 * <p>Server-authored broadcasts (e.g. {@code ChatServer.sendMessageToServerChat}) do NOT pass
 * through {@code sendMessage} and never dispatch this event.
 */
@RequiredArgsConstructor
public class OnServerChatMessageEvent implements ZomboidEvent {

    @Getter private final ChatMessage message;

    @Override
    public String getName() {
        return "OnServerChatMessage";
    }
}
