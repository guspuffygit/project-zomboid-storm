package io.pzstorm.storm.advice.client.loginqueueovertcp;

import io.pzstorm.storm.client.StormLoginQueueOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code GameClient.sendLoginQueueRequest()}. When the request goes out over TCP the
 * vanilla UDP packet is skipped; on any failure the helper returns {@code false} and the vanilla
 * body runs.
 */
public class SendLoginQueueRequestAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormLoginQueueOverTcp.tryRequest();
    }
}
