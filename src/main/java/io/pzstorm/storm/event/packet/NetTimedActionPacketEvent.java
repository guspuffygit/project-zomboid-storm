package io.pzstorm.storm.event.packet;

import io.pzstorm.storm.lua.StormKahluaTable;
import javax.annotation.Nullable;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.NetTimedActionPacket} is processed on
 * the server.
 */
public class NetTimedActionPacketEvent extends PacketEvent {

    public NetTimedActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public NetTimedActionPacket getPacket() {
        return (NetTimedActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "NetTimedActionPacketEvent";
    }

    public PlayerID getPlayerId() {
        return (PlayerID) getField("playerId");
    }

    public String getActionType() {
        return getPacket().type;
    }

    public String getActionName() {
        return getPacket().name;
    }

    public StormKahluaTable getAction() {
        return new StormKahluaTable(getPacket().action);
    }

    public @Nullable PZNetKahluaTableImpl getActionArgs() {
        return (PZNetKahluaTableImpl) getField("actionArgs");
    }

    public @Nullable Boolean getIsUsingTimeout() {
        return (Boolean) getField("isUsingTimeout");
    }
}
