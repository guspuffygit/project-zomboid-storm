package io.pzstorm.storm.advice.gameserverworkshopitems;

import io.pzstorm.storm.core.StormWorkshopUpdateGuard;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.network.GameServerWorkshopItems.Install(ArrayList)}. Runs after Steam
 * has finished installing every pending workshop item and asks the guard whether any jar Storm
 * cataloged in premain has moved on disk; if so the guard exits the JVM so the supervisor can
 * restart with the freshly downloaded jars.
 *
 * <p>Only fires when {@code Install} reports success ({@code @Advice.Return boolean == true}). The
 * other exit paths either short-circuit on a non-server JVM or signal a Steam-side failure where
 * the server would refuse to start anyway.
 */
public class GameServerWorkshopItemsInstallAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return boolean installedOk) {
        if (!installedOk) {
            return;
        }
        StormWorkshopUpdateGuard.checkAndExitIfJarsChanged();
    }
}
