package io.pzstorm.storm.advice.client.loginqueueovertcp;

import io.pzstorm.storm.client.StormLoginQueueOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code GameClient.sendLoginQueueDone(long)}. A 200 from the server replaces the
 * vanilla echo packet and marks loading done; otherwise the vanilla UDP packet is sent.
 */
public class SendLoginQueueDoneAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) long loadingMillis) {
        return StormLoginQueueOverTcp.tryDone(loadingMillis);
    }
}
