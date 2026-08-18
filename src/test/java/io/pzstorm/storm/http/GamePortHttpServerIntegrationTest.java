package io.pzstorm.storm.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GamePortHttpServerIntegrationTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpClient client;

    @BeforeEach
    void setUp() {
        HttpEndpointDispatcher.reset();
        GameHttpEndpointDispatcher.reset();
        StormEventDispatcher.registerEventHandler(StormBuiltinEndpoints.class);
        StormEventDispatcher.registerEventHandler(GamePortBuiltinEndpoints.class);
        StormEventDispatcher.registerEventHandler(GamePortHandshakeEndpoints.class);
        StormEventDispatcher.registerEventHandler(GameTypedBodyEchoEndpoints.class);
        StormHttpServer.start(0);
        GamePortHttpServer.start(new InetSocketAddress(0));
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void tearDown() {
        GamePortHttpServer.stop();
        StormHttpServer.stop();
        HttpEndpointDispatcher.reset();
        GameHttpEndpointDispatcher.reset();
    }

    @Test
    void pingEndpointReturnsStormVersion() throws Exception {
        HttpResponse<String> response = get(GamePortHttpServer.getPort(), "/storm/ping");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(StormVersion.getVersion(), response.body());
    }

    @Test
    void backendEndpointsAreNotServedOnGamePort() {
        // The game port is internet-facing; @HttpEndpoint surfaces (health, client mod
        // downloads, hot-reload) must never leak onto it.
        Assertions.assertEquals(404, statusOf(GamePortHttpServer.getPort(), "/health"));
        Assertions.assertEquals(404, statusOf(GamePortHttpServer.getPort(), "/storm/version"));
    }

    @Test
    void gameEndpointsAreNotServedOnBackendPort() {
        Assertions.assertEquals(404, statusOf(StormHttpServer.getPort(), "/storm/ping"));
    }

    @Test
    void bothServersServeTheirOwnRegistry() throws Exception {
        Assertions.assertEquals(200, statusOf(GamePortHttpServer.getPort(), "/storm/ping"));
        Assertions.assertEquals(200, statusOf(StormHttpServer.getPort(), "/health"));
    }

    @Test
    void dispatcherAndHandlerThreadsAreDaemon() throws Exception {
        // A non-daemon thread keeps a closed game's JVM alive forever: PZ's quit path never
        // calls System.exit, so the zombie sits invisibly on workshop file locks.
        get(GamePortHttpServer.getPort(), "/storm/ping");

        Thread.getAllStackTraces().keySet().stream()
                .filter(
                        t ->
                                t.getName().equals("HTTP-Dispatcher")
                                        || t.getName().startsWith("storm-gameport-http-"))
                .forEach(
                        t ->
                                Assertions.assertTrue(
                                        t.isDaemon(),
                                        "thread " + t.getName() + " must not pin the JVM"));
    }

    @Test
    void typedBodyHandlerDeserializesValidJson() throws Exception {
        HttpResponse<String> response =
                postJson(
                        GamePortHttpServer.getPort(),
                        "/test/game-echo",
                        "{\"name\":\"alice\",\"count\":7}");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("{\"name\":\"alice\",\"count\":7}", response.body());
    }

    @Test
    void typedBodyHandlerRejectsMalformedJson() throws Exception {
        HttpResponse<String> response =
                postJson(GamePortHttpServer.getPort(), "/test/game-echo", "{not json");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(
                response.body().startsWith("invalid JSON:"),
                "expected invalid-JSON message, got: " + response.body());
    }

    @Test
    void handshakeWithoutMatchingGameConnectionIsRejected() throws Exception {
        // No RakNet engine is running in this JVM, so no claim can bind — must be a clean 403
        // telling the client to fall back to UDP, never a 5xx.
        HttpResponse<String> response =
                postJson(
                        GamePortHttpServer.getPort(),
                        "/storm/handshake",
                        "{\"steamId\":\"76561198000000000\",\"stormVersion\":\"2.5.1\"}");

        Assertions.assertEquals(403, response.statusCode());
    }

    @Test
    void handshakeRejectsNonNumericSteamId() throws Exception {
        HttpResponse<String> response =
                postJson(
                        GamePortHttpServer.getPort(),
                        "/storm/handshake",
                        "{\"steamId\":\"not-a-steam-id\"}");

        Assertions.assertEquals(400, response.statusCode());
    }

    @Test
    void startIsIdempotent() {
        int port = GamePortHttpServer.getPort();
        GamePortHttpServer.start(new InetSocketAddress(0));

        Assertions.assertEquals(port, GamePortHttpServer.getPort());
        Assertions.assertTrue(GamePortHttpServer.isRunning());
    }

    private int statusOf(int port, String path) {
        try {
            return get(port, path).statusCode();
        } catch (Exception e) {
            throw new AssertionError("request to port " + port + " path " + path + " failed", e);
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(int port, String path, String body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static class GameTypedBodyEchoEndpoints {

        public record EchoRequest(@JsonProperty(required = true) String name, int count) {}

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @GameHttpEndpoint(path = "/test/game-echo", method = "POST")
        public static void echo(HttpRequestEvent event, EchoRequest body) throws IOException {
            event.sendJson(200, MAPPER.writeValueAsString(body));
        }
    }
}
