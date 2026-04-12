package io.pzstorm.storm.advice.nta;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Advice for {@code NetTimedActionPacket.processClient()}. Logs when the client receives a server
 * response packet.
 */
public class ProcessClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This NetTimedActionPacket self, @Advice.Argument(0) UdpConnection connection) {
        if (NtaDebugLog.isAllowedConnection(connection)) {
            NtaDebugLog.log(
                    "CLIENT",
                    "processClient ENTER: received packet="
                            + NtaDebugLog.describe(self)
                            + " currentQueue="
                            + NtaDebugLog.describeQueue());
        }
    }
}
