package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import java.io.IOException;
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
    private int savedAnimalLOSInterval;

    @BeforeEach
    void setUp() {
        HttpEndpointDispatcher.reset();
        StormEventDispatcher.registerEventHandler(StormBuiltinEndpoints.class);
        StormEventDispatcher.registerEventHandler(TypedBodyEchoEndpoints.class);
        StormHttpServer.start(0);
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        savedAnimalLOSInterval = AnimalLOSTickInterval.getCurrentTickInterval();
    }

    @AfterEach
    void tearDown() {
        StormHttpServer.stop();
        HttpEndpointDispatcher.reset();
        AnimalLOSTickInterval.setTickInterval(savedAnimalLOSInterval);
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

    @Test
    void animalLOSTickIntervalGetReturnsCurrentInterval() throws Exception {
        AnimalLOSTickInterval.setTickInterval(7);

        HttpResponse<String> response = get("/storm/animalLOS/tickInterval");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("{\"tickInterval\":7}", response.body());
    }

    @Test
    void animalLOSTickIntervalPostUpdatesInterval() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval?ticks=12");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("{\"requested\":12,\"applied\":12}", response.body());
        Assertions.assertEquals(12, AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void animalLOSTickIntervalPostZeroDisablesLOS() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval?ticks=0");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("{\"requested\":0,\"applied\":0}", response.body());
        Assertions.assertEquals(0, AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void animalLOSTickIntervalPostClampsAboveMaximum() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval?ticks=99999");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(
                "{\"requested\":99999,\"applied\":" + AnimalLOSTickInterval.MAX_TICK_INTERVAL + "}",
                response.body());
        Assertions.assertEquals(
                AnimalLOSTickInterval.MAX_TICK_INTERVAL,
                AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void animalLOSTickIntervalPostClampsBelowMinimum() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval?ticks=-2");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(
                "{\"requested\":-2,\"applied\":" + AnimalLOSTickInterval.MIN_TICK_INTERVAL + "}",
                response.body());
        Assertions.assertEquals(
                AnimalLOSTickInterval.MIN_TICK_INTERVAL,
                AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void animalLOSTickIntervalPostRejectsMissingTicksParam() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(response.body().contains("missing required query parameter: ticks"));
    }

    @Test
    void animalLOSTickIntervalPostRejectsNonInteger() throws Exception {
        HttpResponse<String> response = post("/storm/animalLOS/tickInterval?ticks=fast");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(response.body().contains("ticks must be an integer"));
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

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + StormHttpServer.getPort() + path))
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void typedBodyHandlerDeserializesValidJson() throws Exception {
        HttpResponse<String> response = postJson("/test/echo", "{\"name\":\"alice\",\"count\":7}");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("{\"name\":\"alice\",\"count\":7}", response.body());
    }

    @Test
    void typedBodyHandlerRejectsMalformedJson() throws Exception {
        HttpResponse<String> response = postJson("/test/echo", "{not json");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(
                response.body().startsWith("invalid JSON:"),
                "expected invalid-JSON message, got: " + response.body());
    }

    @Test
    void typedBodyHandlerRejectsEmptyBody() throws Exception {
        HttpResponse<String> response = postJson("/test/echo", "");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertEquals("missing request body", response.body());
    }

    @Test
    void typedBodyHandlerRejectsBlankBody() throws Exception {
        HttpResponse<String> response = postJson("/test/echo", "   \n  ");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertEquals("missing request body", response.body());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + StormHttpServer.getPort() + path))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static class TypedBodyEchoEndpoints {

        public record EchoRequest(String name, int count) {}

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @HttpEndpoint(path = "/test/echo", method = "POST")
        public static void echo(HttpRequestEvent event, EchoRequest body) throws IOException {
            event.sendJson(200, MAPPER.writeValueAsString(body));
        }
    }
}
