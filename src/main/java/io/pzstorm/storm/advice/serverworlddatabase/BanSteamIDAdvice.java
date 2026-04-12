package io.pzstorm.storm.advice.serverworlddatabase;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnBanSteamIDEvent;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code ServerWorldDatabase.banSteamID(String, String, boolean)}. Dispatches an {@link
 * OnBanSteamIDEvent} after the original method has finished executing.
 */
public class BanSteamIDAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String steamID,
            @Advice.Argument(1) String reason,
            @Advice.Argument(2) boolean ban,
            @Advice.Return String result) {
        StormEventDispatcher.dispatchEvent(new OnBanSteamIDEvent(steamID, reason, ban, result));
    }
}
