package io.pzstorm.storm.advice.bodydamagesync;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.patch.fixes.BodyDamageUpdatePacketPatch;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code BodyDamageUpdatePacket.parse(ByteBufferReader, IConnection)}. After vanilla
 * parse has resolved players, delegates to {@link BodyDamageUpdatePacketPatch#repairPlayerIds} to
 * rewrite a stale wire id of {@code -1} to the connection-resolved player's real online id. The
 * {@code self} parameter is typed {@link Object} so this advice never references the transform
 * target.
 */
public class PacketParseAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object self) {
        try {
            BodyDamageUpdatePacketPatch.repairPlayerIds(self);
        } catch (Throwable t) {
            LOGGER.error("BodyDamageUpdatePacket player-id repair failed", t);
        }
    }
}
