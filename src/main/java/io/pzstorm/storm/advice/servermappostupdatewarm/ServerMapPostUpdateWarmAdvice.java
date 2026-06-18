package io.pzstorm.storm.advice.servermappostupdatewarm;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Body-replacement advice for {@code ServerMap.postupdate}. When cell warming is enabled, delegates
 * the entire postupdate loop to {@link StormCellWarmer#runPostUpdate(ServerMap)} so warm cells stay
 * resident in {@code cellMap}/{@code loadedCells} instead of being destructively unloaded. When
 * warming is disabled, returns 0 to let vanilla run unchanged.
 *
 * <p>Stacks under the existing {@code ServerMapPostUpdateAdvice} timing wrapper — both advices'
 * enter/exit hooks fire regardless of body skip, so per-tick timings continue to record the elapsed
 * wall-clock even on the warm-aware code path.
 */
public class ServerMapPostUpdateWarmAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object thisObj) {
        if (!GameServer.server) {
            return 0;
        }
        if (!StormCellWarmingConfig.isEnabled()) {
            return 0;
        }
        if (!(thisObj instanceof ServerMap serverMap)) {
            return 0;
        }
        StormCellWarmer.runPostUpdate(serverMap);
        return 1;
    }
}
