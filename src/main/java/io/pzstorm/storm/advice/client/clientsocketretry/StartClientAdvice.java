package io.pzstorm.storm.advice.client.clientsocketretry;

import io.pzstorm.storm.client.StormClientSocketRetry;
import net.bytebuddy.asm.Advice;

/**
 * Exit hook on {@code GameClient.startClient()}. Vanilla runs first and this only acts when it left
 * {@code clientStarted} false, which it does when the RakNet socket could not bind.
 */
public class StartClientAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.FieldValue(value = "clientStarted", readOnly = false) boolean clientStarted) {
        clientStarted = StormClientSocketRetry.afterStartClient(self, clientStarted);
    }
}
