package io.pzstorm.storm.advice.worldmapvisitedsend;

import io.pzstorm.storm.map.StormMapAllKnownSend;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code WorldMapVisitedServer.sendRequestData} through {@link StormMapAllKnownSend#send}: a
 * {@code true} verdict means the all-known visited data has been written and the vanilla body is
 * skipped; {@code false} (option off, client JVM, or failure latch) leaves the vanilla body to run
 * untouched.
 */
public class WorldMapVisitedSendAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object server,
            @Advice.Argument(0) Object connection,
            @Advice.Argument(1) Object writer) {
        if (!GameServer.server) {
            return false;
        }
        return StormMapAllKnownSend.send(server, connection, writer);
    }
}
