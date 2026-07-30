package io.pzstorm.storm.advice.connectionevents;

import io.pzstorm.storm.metrics.StormConnectionEventMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Counts {@code ConnectionManager.log(String, String, IConnection)} calls, skipping the
 * null-argument path — that one delegates to the {@code long} overload, which {@link
 * ConnectionManagerLogGuidAdvice} already counts.
 */
public class ConnectionManagerLogConnectionAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) String source,
            @Advice.Argument(1) String event,
            @Advice.Argument(2) Object connection) {
        if (!GameServer.server) {
            return;
        }
        if (connection == null) {
            return;
        }
        StormConnectionEventMetrics.record(source, event);
    }
}
