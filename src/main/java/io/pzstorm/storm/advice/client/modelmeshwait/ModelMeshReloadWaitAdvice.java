package io.pzstorm.storm.advice.client.modelmeshwait;

import net.bytebuddy.asm.Advice;
import zombie.network.GameClient;

/**
 * Exit hook on {@code ModelManager.initAnimationMeshes(boolean)}. The boot pass ({@code false})
 * already waits inside the method; only the reload pass needs the wait.
 */
public class ModelMeshReloadWaitAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Argument(0) boolean reloading) {
        if (!reloading || !GameClient.client) {
            return;
        }
        ModelMeshReloadWait.waitForMeshes();
    }
}
