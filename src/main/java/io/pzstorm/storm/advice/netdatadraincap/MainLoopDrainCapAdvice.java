package io.pzstorm.storm.advice.netdatadraincap;

import io.pzstorm.storm.metrics.NetDataMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.ZomboidNetData;
import zombie.network.ZomboidNetDataPool;

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
 * GameServer.java:902-915}): they are not re-queued, and they are gone for good — by the time a
 * packet reaches this queue RakNet has already ACKed it at the transport layer, so RakNet
 * retransmission never covers an application-level drop, reliable or not. What bounds the damage is
 * that the bulk of the per-spin drain volume is periodic state (player updates, zombie/animal sync,
 * pings) regenerated every tick; a one-shot reliable packet (item transaction, action, chat) lost
 * while the cap is engaged desyncs until the next authoritative resync — the same tradeoff vanilla
 * accepts when its 70&nbsp;ms cycle cap drops the tail of the vehicle queue, VehiclePhysicsReliable
 * included.
 *
 * <p>Gated on {@link GameServer#server} as defense-in-depth; the patch itself is registered only
 * when {@link io.pzstorm.storm.util.StormEnv#isStormServer()} (HARD RULE: no Storm patches on the
 * client JVM).
 */
public class MainLoopDrainCapAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) ZomboidNetData data) {
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
            // The skipped body's own drop paths end in ZomboidNetDataPool.instance.discard(d);
            // without this every deferral strands a pooled 2 KB ZomboidNetData, so a sustained
            // burst drains the pool and turns every later packet into a fresh allocation.
            if (data != null) {
                ZomboidNetDataPool.instance.discard(data);
            }
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
