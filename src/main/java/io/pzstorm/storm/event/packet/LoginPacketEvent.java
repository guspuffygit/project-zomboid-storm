package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.LoginPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.LoginPacket} is processed on
 * the server.
 */
public class LoginPacketEvent extends PacketEvent {

    public LoginPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public LoginPacket getPacket() {
        return (LoginPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "LoginPacketEvent";
    }
}
