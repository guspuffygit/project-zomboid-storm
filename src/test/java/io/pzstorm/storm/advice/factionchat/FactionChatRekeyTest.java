package io.pzstorm.storm.advice.factionchat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.UnitTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zombie.characters.Faction;
import zombie.chat.defaultChats.FactionChat;

class FactionChatRekeyTest implements UnitTest {

    @AfterEach
    void clearChats() throws Exception {
        FactionChatRekey.factionChats().clear();
    }

    @Test
    void renameMovesChatToNewName() throws Exception {
        Map<String, FactionChat> chats = FactionChatRekey.factionChats();
        FactionChat chat = new FactionChat(17, null);
        chats.put("GOLD", chat);

        FactionChatRekey.rekey(faction("Gold"), "GOLD");

        assertSame(chat, chats.get("Gold"));
        assertFalse(chats.containsKey("GOLD"));
    }

    @Test
    void renameOntoAnotherFactionsChatLeavesBothInPlace() throws Exception {
        Map<String, FactionChat> chats = FactionChatRekey.factionChats();
        FactionChat ours = new FactionChat(17, null);
        FactionChat theirs = new FactionChat(18, null);
        chats.put("GOLD", ours);
        chats.put("Gold", theirs);

        FactionChatRekey.rekey(faction("Gold"), "GOLD");

        assertSame(ours, chats.get("GOLD"));
        assertSame(theirs, chats.get("Gold"));
    }

    private static Faction faction(String name) {
        Faction faction = new Faction();
        faction.setName(name);
        faction.setOwner("owner");
        return faction;
    }
}
