package io.pzstorm.storm.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.core.StormEventDispatcher;
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

    @BeforeEach
    void setUp() {
        HttpEndpointDispatcher.reset();
        StormEventDispatcher.registerEventHandler(StormBuiltinEndpoints.class);
        StormEventDispatcher.registerEventHandler(TypedBodyEchoEndpoints.class);
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

    @Test
    void typedBodyHandlerRejectsMissingRequiredField() throws Exception {
        HttpResponse<String> response = postJson("/test/echo", "{\"count\":7}");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(
                response.body().startsWith("invalid JSON:") && response.body().contains("name"),
                "expected invalid-JSON message naming the missing field, got: " + response.body());
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

        public record EchoRequest(@JsonProperty(required = true) String name, int count) {}

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @HttpEndpoint(path = "/test/echo", method = "POST")
        public static void echo(HttpRequestEvent event, EchoRequest body) throws IOException {
            event.sendJson(200, MAPPER.writeValueAsString(body));
        }
    }
}
