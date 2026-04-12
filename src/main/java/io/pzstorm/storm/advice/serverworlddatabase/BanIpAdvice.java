package io.pzstorm.storm.advice.serverworlddatabase;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnBanIpEvent;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code ServerWorldDatabase.banIp(String, String, String, boolean)}. Dispatches an
 * {@link OnBanIpEvent} after the original method has finished executing.
 */
public class BanIpAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String ip,
            @Advice.Argument(1) String username,
            @Advice.Argument(2) String reason,
            @Advice.Argument(3) boolean ban,
            @Advice.Return String result) {
        StormEventDispatcher.dispatchEvent(new OnBanIpEvent(ip, username, reason, ban, result));
    }
}
