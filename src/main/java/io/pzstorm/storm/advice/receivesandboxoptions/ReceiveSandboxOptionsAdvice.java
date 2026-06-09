package io.pzstorm.storm.advice.receivesandboxoptions;

import io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier;
import net.bytebuddy.asm.Advice;

/**
 * Re-applies Storm's performance sandbox options after {@code
 * GameServer.receiveSandboxOptions(ByteBufferReader, UdpConnection, short)} finishes. Vanilla loads
 * the new option values into {@code SandboxOptions.instance} but fires no Lua event, so the Storm
 * controllers (and their Prometheus gauges) need a direct re-read from the very same method that
 * applied the change.
 *
 * <p>Owning transformer {@code ReceiveSandboxOptionsPatch} is registration-gated server-only.
 */
public class ReceiveSandboxOptionsAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        StormPerformanceSandboxApplier.applyAll();
    }
}
