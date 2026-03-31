package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.LoginQueueDonePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.LoginQueueDonePacket} is
 * processed on the server.
 */
public class LoginQueueDonePacketEvent extends PacketEvent {

    public LoginQueueDonePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public LoginQueueDonePacket getPacket() {
        return (LoginQueueDonePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "LoginQueueDonePacketEvent";
    }
}
