package io.pzstorm.storm.advice.client.playerprofileovertcp;

import io.pzstorm.storm.client.StormPlayerProfilesOverTcp;
import net.bytebuddy.asm.Advice;

/**
 * Entry hook on {@code ClientPlayerDB.clientLoadNetworkPlayer()} (loader thread). The helper
 * prefetches the network-character profile over TCP; the vanilla body then takes its own no-network
 * fast path, or falls back to the UDP request/poll loop if nothing was installed.
 */
public class ClientLoadNetworkPlayerAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        StormPlayerProfilesOverTcp.tryPrefetch();
    }
}
