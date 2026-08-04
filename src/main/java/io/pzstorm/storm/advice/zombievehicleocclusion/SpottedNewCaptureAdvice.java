package io.pzstorm.storm.advice.zombievehicleocclusion;

import io.pzstorm.storm.los.ZombieVehicleOcclusion;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Captures the {@code other} and {@code bForced} arguments of {@code IsoZombie.spottedNew} so
 * {@link IsoZombieVehicleBetweenAdvice} can apply result-unused guards — {@code isVehicleBetween}
 * itself only receives coordinates. {@code isVehicleBetween} has exactly one call site, inside
 * {@code spottedNew}, so the capture is always fresh when it is read.
 *
 * <p>The exit advice clears the target reference (on exceptional exit too) so a disconnected
 * player's object graph is not pinned by the static field.
 */
public class SpottedNewCaptureAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) Object other, @Advice.Argument(1) boolean bForced) {
        if (!GameServer.server) {
            return;
        }
        ZombieVehicleOcclusion.spottedTarget = other;
        ZombieVehicleOcclusion.spottedForced = bForced;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        if (!GameServer.server) {
            return;
        }
        ZombieVehicleOcclusion.spottedTarget = null;
    }
}
