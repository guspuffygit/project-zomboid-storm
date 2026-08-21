package io.pzstorm.storm.advice.movingobjectschedulerstartframe;

import io.pzstorm.storm.spatial.StormSpatialIndex;
import net.bytebuddy.asm.Advice;
import zombie.MovingObjectUpdateScheduler;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Rebuilds {@link StormSpatialIndex} as {@code MovingObjectUpdateScheduler.startFrame()} returns:
 * the frame counter has been bumped and the scheduler has just walked the same {@code objectList},
 * nothing has moved yet this tick.
 */
public class MovingObjectSchedulerStartFrameAdvice {

    @Advice.OnMethodExit
    public static void onExit() {
        if (!GameServer.server) {
            return;
        }
        StormSpatialIndex.rebuild(
                IsoWorld.instance.getCell().getObjectList(),
                MovingObjectUpdateScheduler.instance.getFrameCounter());
    }
}
