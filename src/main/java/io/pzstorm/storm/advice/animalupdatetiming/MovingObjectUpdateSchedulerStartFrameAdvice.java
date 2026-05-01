package io.pzstorm.storm.advice.animalupdatetiming;

import io.pzstorm.storm.metrics.AnimalUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Advice for {@code MovingObjectUpdateScheduler.startFrame()}.
 *
 * <p>Increments the server-tick counter once per frame so {@link AnimalUpdateMetrics} can compute
 * an average animal-update cost per tick.
 */
public class MovingObjectUpdateSchedulerStartFrameAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        AnimalUpdateMetrics.recordTick();
    }
}
