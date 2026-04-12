package io.pzstorm.storm.advice.packet;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.network.PacketTypes;

/**
 * Advice for {@code PacketTypes.PacketType.onClientPacket()}. Logs every packet received by the
 * client when our Steam ID is in the allow list.
 */
public class OnClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This PacketTypes.PacketType self) {
        if (!NtaDebugLog.isAllowedClient()) return;

        NtaDebugLog.log("CLIENT", "PACKET-RECV type=" + self.name());
    }
}
