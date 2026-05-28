package io.pzstorm.storm.advice.isoroomonsee;

import io.pzstorm.storm.los.StormServerLos;
import net.bytebuddy.asm.Advice;

/**
 * Serializes {@code IsoRoom.onSee} across LOS workers. {@code onSee} mutates building-wide state
 * ({@code roomSpotted}, {@code StashSystem.visitedBuilding}, {@code RoomDef.explored}) that vanilla
 * already invokes repeatedly but never concurrently. Under parallel LOS, two workers can enter
 * {@code onSee} for the same building at once; this guard takes a global lock so those calls run
 * one at a time, matching the single-threaded execution they were written for. No-op at {@code
 * threads < 2}, so the default path is untouched.
 */
public class IsoRoomOnSeeAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter() {
        return StormServerLos.lockOnSee();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean locked) {
        if (locked) {
            StormServerLos.unlockOnSee();
        }
    }
}
