package io.pzstorm.storm.advice.gameserverworkshopitems;

import io.pzstorm.storm.core.StormWorkshopInstallRecovery;
import io.pzstorm.storm.core.StormWorkshopUpdateGuard;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.network.GameServerWorkshopItems.Install(ArrayList)}.
 *
 * <p>On a successful install, asks {@link StormWorkshopUpdateGuard} whether any jar Storm cataloged
 * in premain has moved on disk; if so the guard exits the JVM so the supervisor can restart with
 * the freshly downloaded jars.
 *
 * <p>On failure - {@code Install} returning false or throwing (vanilla NPEs through {@code
 * ZomboidFileSystem.deleteDirectory(null)} when a failing item was never installed) - hands off to
 * {@link StormWorkshopInstallRecovery}, which logs a per-item diagnosis of why Steam refuses each
 * unavailable item (moderation-removed, deleted, hidden, or a transient post-update manifest
 * deny-window) and retries the install a few times. When a retry succeeds the thrown exception is
 * swallowed and the return value flipped to true; otherwise the failure stands and the server does
 * not start, matching vanilla.
 */
public class GameServerWorkshopItemsInstallAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) ArrayList<Long> itemIDList,
            @Advice.Return(readOnly = false) boolean installedOk,
            @Advice.Thrown(readOnly = false) Throwable thrown) {
        if (thrown != null || !installedOk) {
            if (!StormWorkshopInstallRecovery.recover(itemIDList, thrown)) {
                return;
            }
            thrown = null;
            installedOk = true;
        }
        StormWorkshopUpdateGuard.checkAndExitIfJarsChanged();
    }
}
