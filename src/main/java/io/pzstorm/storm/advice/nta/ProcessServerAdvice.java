package io.pzstorm.storm.advice.nta;

import io.pzstorm.storm.patch.fixes.NetTimedActionPacketFix;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Advice for {@code NetTimedActionPacket.processServer()}. Hands the packet to {@link
 * NetTimedActionPacketFix#onProcessServer} for diagnostics; the vanilla body always runs.
 */
public class ProcessServerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This NetTimedActionPacket self, @Advice.Argument(1) UdpConnection connection) {
        NetTimedActionPacketFix.onProcessServer(self, connection);
    }
}
