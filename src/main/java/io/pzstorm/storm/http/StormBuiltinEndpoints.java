package io.pzstorm.storm.http;

import io.pzstorm.storm.core.StormVersion;
import java.io.IOException;

/** Endpoints always registered by Storm when the HTTP server is enabled. */
public class StormBuiltinEndpoints {

    @HttpEndpoint(path = "/health")
    public static void health(HttpRequestEvent event) throws IOException {
        event.send(200, "OK");
    }

    @HttpEndpoint(path = "/storm/version")
    public static void version(HttpRequestEvent event) throws IOException {
        event.send(200, StormVersion.getVersion());
    }
}
