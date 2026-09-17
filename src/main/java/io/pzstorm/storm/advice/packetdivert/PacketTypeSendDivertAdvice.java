package io.pzstorm.storm.advice.packetdivert;

import io.pzstorm.storm.connection.StormPacketDivert;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code PacketTypes.PacketType.send(IConnection)}. When {@link StormPacketDivert}
 * captures the packet the vanilla body (endPacket → RakNet) is skipped; otherwise it runs
 * untouched.
 */
public class PacketTypeSendDivertAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object type, @Advice.Argument(0) Object connection) {
        return StormPacketDivert.tryDivert(type, connection);
    }
}
