package io.pzstorm.storm.advice.netdatadraincap;

import io.pzstorm.storm.metrics.NetDataMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Per-spin wall-clock cap for {@code GameServer.mainLoopDealWithNetData}.
 *
 * <p>Wraps every invocation. The first call after a >{@link MainLoopDrainCap#BURST_GAP_NANOS} gap
 * resets the burst-start timestamp; once accumulated elapsed since the burst-start exceeds the
 * configured cap, subsequent calls in the same burst short-circuit (the original method body is
 * skipped via Byte Buddy's {@code skipOn} mechanism). The next outer-loop iteration — separated
 * from the current one by either {@code Thread.sleep(5)} or the ~80 ms frame-step block — starts a
 * new burst with a fresh budget.
 *
 * <p>Dropped packets behave like overflow from the existing vehicle-queue cap ({@code
 * GameServer.java:902-915}): they are not re-queued. Clients re-send reliable packets via RakNet
 * retransmission, so connection-handshake packets (LoginPacket → ConnectionDetails) are not lost in
 * practice. Per-tick player-update packets are best-effort and the client interpolates over a
 * single missed update.
 *
 * <p>Gated on {@link GameServer#server} as defense-in-depth; the patch itself is registered only
 * when {@link io.pzstorm.storm.util.StormEnv#isStormServer()} (HARD RULE: no Storm patches on the
 * client JVM).
 */
public class MainLoopDrainCapAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        if (!GameServer.server) {
            return false;
        }
        long cap = MainLoopDrainCap.getCapNanos();
        if (cap <= 0L) {
            return false;
        }
        long now = System.nanoTime();
        if (now - MainLoopDrainCap.lastCallEndNanos > MainLoopDrainCap.BURST_GAP_NANOS) {
            MainLoopDrainCap.burstStartNanos = now;
        }
        if (now - MainLoopDrainCap.burstStartNanos > cap) {
            NetDataMetrics.recordDeferred();
            return true;
        }
        return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        if (!GameServer.server) {
            return;
        }
        MainLoopDrainCap.lastCallEndNanos = System.nanoTime();
    }
}
