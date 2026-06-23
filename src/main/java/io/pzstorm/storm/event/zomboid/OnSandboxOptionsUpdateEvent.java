package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/**
 * Dispatched server-side after {@code GameServer.receiveSandboxOptions(ByteBufferReader,
 * UdpConnection, short)} finishes loading new option values into {@code SandboxOptions.instance}.
 * Vanilla fires no Lua event on this path, so subscribers that need to react to a runtime sandbox
 * push (e.g. reapply derived state, refresh Prometheus gauges) must use this event.
 *
 * <p>Only fires on the dedicated server.
 */
public class OnSandboxOptionsUpdateEvent implements ZomboidEvent {

    @Override
    public String getName() {
        return "OnSandboxOptionsUpdate";
    }
}
