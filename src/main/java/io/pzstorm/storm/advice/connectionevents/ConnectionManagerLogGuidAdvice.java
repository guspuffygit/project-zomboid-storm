package io.pzstorm.storm.advice.connectionevents;

import io.pzstorm.storm.metrics.StormConnectionEventMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/** Counts {@code ConnectionManager.log(String, String, long)} calls. */
public class ConnectionManagerLogGuidAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) String source, @Advice.Argument(1) String event) {
        if (!GameServer.server) {
            return;
        }
        StormConnectionEventMetrics.record(source, event);
    }
}
