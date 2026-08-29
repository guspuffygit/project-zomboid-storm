package io.pzstorm.storm.advice.nta;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.patch.fixes.NetTimedActionPacketFix;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Advice for {@code NetTimedActionPacket.processServer()}. Delegates to {@link
 * NetTimedActionPacketFix#processServerFixed} which contains the corrected logic.
 */
public class ProcessServerAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This NetTimedActionPacket self, @Advice.Argument(1) UdpConnection connection) {
        try {
            return NetTimedActionPacketFix.processServerFixed(self, connection);
        } catch (Exception e) {
            LOGGER.error("Unable to run NetTimedActionPacketFix.processServerFixed", e);
            return false;
        }
    }
}
