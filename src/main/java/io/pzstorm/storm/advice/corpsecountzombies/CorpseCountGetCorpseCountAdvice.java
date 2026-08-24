package io.pzstorm.storm.advice.corpsecountzombies;

import io.pzstorm.storm.spatial.StormCorpseZombieCount;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code CorpseCount.getCorpseCount(int, int, int, IsoBuilding)}: adds the
 * spatial-index-derived zombie count via {@link StormCorpseZombieCount#augment} when the fast path
 * served this call (the companion {@code MemberSubstitution} in {@code CorpseCountZombieIndexPatch}
 * steered the method past vanilla's 25×25 walk); a no-op when vanilla's own walk ran.
 */
public class CorpseCountGetCorpseCountAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) int wx,
            @Advice.Argument(1) int wy,
            @Advice.Argument(2) int z,
            @Advice.Argument(3) Object building,
            @Advice.Return(readOnly = false) int count) {
        count = StormCorpseZombieCount.augment(count, wx, wy, z, building);
    }
}
