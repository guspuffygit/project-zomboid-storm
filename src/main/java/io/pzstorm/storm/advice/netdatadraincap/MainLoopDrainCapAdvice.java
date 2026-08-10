package io.pzstorm.storm.advice.netdatadraincap;

import io.pzstorm.storm.metrics.NetDataMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
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
 * included. Two exemptions: {@code VehicleRequest}, and any packet from a connection that has not
 * finished the join handshake ({@link MainLoopDrainCap#isPreJoinExempt} — the login funnel is
 * one-shot and unretried, so a single drop strands the join).
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
            // VehicleRequest is the ONLY inbound path that makes the server queue a
            // VehicleFullUpdate for a client that lost a vehicle (state.flags |= Full). Vanilla's
            // own 70 ms shed drops nothing but the VehiclePhysics* queue and deliberately never
            // touches requests; dropping one here leaves the car invisible until the client's
            // next 1 Hz retry, which the same engaged cap is likely to drop again. Volume is
            // bounded — one batched packet per client per 100 ms — so processing it under the cap
            // is cheap.
            if (data != null && data.type == PacketTypes.PacketType.VehicleRequest) {
                NetDataMetrics.recordVehicleRequestExempt();
                return false;
            }
            // The login funnel is one-shot and never retried by the vanilla client; dropping any
            // of it silently strands the join. See MainLoopDrainCap.isPreJoinExempt.
            if (data != null && MainLoopDrainCap.isPreJoinExempt(data)) {
                return false;
            }
            NetDataMetrics.recordDropped();
            // The skipped body's own drop paths end in ZomboidNetDataPool.instance.discard(d);
            // without this every drop strands a pooled 2 KB ZomboidNetData, so a sustained
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
