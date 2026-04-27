package io.pzstorm.storm.advice.generalactionpacket;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.patch.fixes.GeneralActionPacketPatch;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.GeneralActionPacket;

/**
 * Advice for {@code GeneralActionPacket.processServer()}. Delegates to {@link
 * GeneralActionPacketPatch#processServerFixed} which resolves the cancelling player from the
 * sending {@link UdpConnection} (vanilla {@code setReject} on the client never populates {@code
 * playerId}, so the packet alone can't be matched against the queue under {@link
 * io.pzstorm.storm.advice.actionmanager.StopAdvice}).
 */
public class ProcessServerAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This GeneralActionPacket self, @Advice.Argument(1) UdpConnection connection) {
        try {
            return GeneralActionPacketPatch.processServerFixed(self, connection);
        } catch (Exception e) {
            LOGGER.error("GeneralActionPacketPatch.processServerFixed failed, falling back", e);
            return false;
        }
    }
}
