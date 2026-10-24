package io.pzstorm.storm.advice.pathfindnativeremovechunk;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;

/**
 * Skips the native pathfinder remove for a chunk of an evicted warm cell that {@code
 * StormCellWarmer.evictLiteReconnect} did not re-add, so the native side never sees the add +
 * remove round trip (see that method's doc). Every other call runs the vanilla body untouched.
 */
public class PathfindNativeEvictRemoveSkipAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) IsoChunk chunk) {
        return StormCellWarmer.consumeEvictPathfindSkip(chunk);
    }
}
