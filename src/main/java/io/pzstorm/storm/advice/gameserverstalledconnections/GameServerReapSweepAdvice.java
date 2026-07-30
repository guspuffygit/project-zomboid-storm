package io.pzstorm.storm.advice.gameserverstalledconnections;

import net.bytebuddy.asm.Advice;

/**
 * Runs the stalled-connection sweep from {@code GameServer.launchCommandHandler}, which the server
 * frame-step block calls exactly once per tick on the main thread. {@link
 * StalledConnectionReaper#sweep()} throttles itself, so the per-tick cost is a clock read.
 */
public class GameServerReapSweepAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StalledConnectionReaper.sweep();
    }
}
