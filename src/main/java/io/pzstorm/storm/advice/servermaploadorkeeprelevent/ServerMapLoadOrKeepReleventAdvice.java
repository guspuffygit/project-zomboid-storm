package io.pzstorm.storm.advice.servermaploadorkeeprelevent;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.ServerMap;

public class ServerMapLoadOrKeepReleventAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object thisObj, @Advice.Argument(0) int x, @Advice.Argument(1) int y) {
        if (!GameServer.server) {
            return 0;
        }
        if (!StormCellWarmingConfig.isEnabled()) {
            return 0;
        }
        if (!(thisObj instanceof ServerMap serverMap)) {
            return 0;
        }
        if (serverMap.isInvalidCell(x, y)) {
            return 0;
        }
        if (serverMap.getCell(x, y) != null) {
            return 0;
        }
        int wx = x + serverMap.getMinX();
        int wy = y + serverMap.getMinY();
        if (!StormCellWarmer.isWarm(wx, wy)) {
            return 0;
        }
        return StormCellWarmer.rewarm(serverMap, wx, wy) ? 1 : 0;
    }
}
