package io.pzstorm.storm.advice.serverworlddatabase;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnBanUserEvent;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code ServerWorldDatabase.banUser(String, boolean)}. Dispatches an {@link
 * OnBanUserEvent} after the original method has finished executing.
 */
public class BanUserAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String username,
            @Advice.Argument(1) boolean ban,
            @Advice.Return String result) {
        StormEventDispatcher.dispatchEvent(new OnBanUserEvent(username, ban, result));
    }
}
