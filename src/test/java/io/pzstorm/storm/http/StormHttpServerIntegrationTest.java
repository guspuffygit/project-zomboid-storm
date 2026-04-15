package io.pzstorm.storm.http;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StormHttpServerIntegrationTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpClient client;

    @BeforeEach
    void setUp() {
        HttpEndpointDispatcher.reset();
        StormEventDispatcher.registerEventHandler(StormBuiltinEndpoints.class);
        StormHttpServer.start(0);
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void tearDown() {
        StormHttpServer.stop();
        HttpEndpointDispatcher.reset();
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        HttpResponse<String> response = get("/health");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("OK", response.body());
    }

    @Test
    void versionEndpointReturnsStormVersion() throws Exception {
        HttpResponse<String> response = get("/storm/version");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(StormVersion.getVersion(), response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + StormHttpServer.getPort() + path))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
