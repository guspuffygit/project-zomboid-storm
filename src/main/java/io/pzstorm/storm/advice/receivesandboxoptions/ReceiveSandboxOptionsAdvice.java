package io.pzstorm.storm.advice.receivesandboxoptions;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnSandboxOptionsUpdateEvent;
import net.bytebuddy.asm.Advice;

/**
 * Fires {@link OnSandboxOptionsUpdateEvent} after {@code
 * GameServer.receiveSandboxOptions(ByteBufferReader, UdpConnection, short)} finishes. Vanilla loads
 * the new option values into {@code SandboxOptions.instance} but fires no Lua event, so Storm and
 * mods that mirror sandbox state need a hook from the very same method that applied the change.
 *
 * <p>Owning transformer {@code ReceiveSandboxOptionsPatch} is registration-gated server-only.
 */
public class ReceiveSandboxOptionsAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        StormEventDispatcher.dispatchEvent(new OnSandboxOptionsUpdateEvent());
    }
}
