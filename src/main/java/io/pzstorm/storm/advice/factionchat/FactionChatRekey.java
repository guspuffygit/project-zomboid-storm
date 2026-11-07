package io.pzstorm.storm.advice.factionchat;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.util.Map;
import zombie.characters.Faction;
import zombie.chat.ChatSettings;
import zombie.chat.defaultChats.FactionChat;
import zombie.network.chat.ChatServer;
import zombie.network.fields.FactionId;

/**
 * Keeps {@code ChatServer.factionChats} keyed by the faction's current name across a rename.
 *
 * <p>Vanilla {@code FactionChangeTitlePacket.processServer} calls {@code Faction.setName} and never
 * touches the chat map, so the chat stays under the old name. Every later lookup by the new name
 * misses: a reconnecting member gets "Faction chat is not initialized!" and no faction chat, an
 * accepted invite gets "Faction chat '...' is not exist", and a disband throws from {@code
 * removeFactionChat}. A server restart hides it because {@code Faction.load} creates the chat under
 * the saved name.
 */
public class FactionChatRekey {

    public static String nameOf(Object packet) {
        try {
            Faction faction = ((FactionId) packet).getFaction();
            return faction == null ? null : faction.getName();
        } catch (Throwable t) {
            LOGGER.error("FactionChat: could not read faction name from title packet", t);
            return null;
        }
    }

    public static void afterRename(Object packet, String oldName) {
        try {
            Faction faction = ((FactionId) packet).getFaction();
            if (faction == null || oldName == null || oldName.equals(faction.getName())) {
                return;
            }
            rekey(faction, oldName);
        } catch (Throwable t) {
            LOGGER.error("FactionChat: re-key after rename from '{}' failed", oldName, t);
        }
    }

    public static void rekey(Faction faction, String oldName) throws ReflectiveOperationException {
        String newName = faction.getName();
        Map<String, FactionChat> chats = factionChats();
        boolean missing;
        synchronized (chats) {

            // A live chat under the new name belongs to another faction with the same name.
            if (chats.containsKey(newName)) {
                LOGGER.warn(
                        "FactionChat: faction '{}' renamed to '{}', which already has a chat;"
                                + " leaving both chats in place",
                        oldName,
                        newName);
                return;
            }
            FactionChat chat = chats.remove(oldName);
            missing = chat == null;
            if (!missing) {
                chats.put(newName, chat);
            }
        }
        if (missing) {
            recreate(faction);
            LOGGER.info(
                    "FactionChat: faction '{}' renamed to '{}' had no chat; created and synced one",
                    oldName,
                    newName);
        } else {
            LOGGER.info("FactionChat: re-keyed chat for faction '{}' -> '{}'", oldName, newName);
        }
    }

    private static void recreate(Faction faction) {
        ChatServer server = ChatServer.getInstance();
        FactionChat chat = server.createFactionChat(faction.getName());
        ChatSettings settings = FactionChat.getDefaultSettings();
        settings.setFontColor(
                faction.getTagColor().r,
                faction.getTagColor().g,
                faction.getTagColor().b,
                faction.getTagColor().a);
        chat.setSettings(settings);
        server.syncFactionChatMembers(faction.getName(), faction.getOwner(), faction.getPlayers());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, FactionChat> factionChats() throws ReflectiveOperationException {
        Field f = ChatServer.class.getDeclaredField("factionChats");
        f.setAccessible(true);
        return (Map<String, FactionChat>) f.get(null);
    }
}
