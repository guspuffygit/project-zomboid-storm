package io.pzstorm.storm.http;

import io.pzstorm.storm.core.StormVersion;
import java.io.IOException;

/**
 * Endpoints always registered for the game-port HTTP server. Everything here is reachable from the
 * open internet — keep responses cheap and free of anything sensitive.
 */
public class GamePortBuiltinEndpoints {

    /**
     * Reachability + capability probe for clients: a 200 with the Storm version tells a client the
     * server has the game-port TCP channel and which feature level to expect. Anything else
     * (refused, timeout, non-Storm response) means "fall back to UDP-only".
     */
    @GameHttpEndpoint(path = "/storm/ping")
    public static void ping(HttpRequestEvent event) throws IOException {
        event.send(200, StormVersion.getVersion());
    }
}
