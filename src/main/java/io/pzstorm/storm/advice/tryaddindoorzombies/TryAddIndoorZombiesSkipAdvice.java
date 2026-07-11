package io.pzstorm.storm.advice.tryaddindoorzombies;

import io.pzstorm.storm.mapscan.MapScanJob;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Skips {@code VirtualZombieManager.tryAddIndoorZombies} entirely while a Storm map scan is
 * running, so the sweep's forced chunk loads don't mass-spawn zombies into every unexplored
 * building — including into the sealed areas the scan exists to find.
 */
public class TryAddIndoorZombiesSkipAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return GameServer.server && MapScanJob.isSuppressingIndoorSpawns();
    }
}
