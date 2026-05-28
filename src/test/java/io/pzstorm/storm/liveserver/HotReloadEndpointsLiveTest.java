package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test for Storm's developer hot-reload endpoints against a live dedicated server. The
 * server is launched by {@link ServerExtension} with {@code -Dstorm.hotreload=true} and {@code
 * -Dstorm.http.port=}{@link ServerExtension#TEST_HTTP_PORT}, so both {@code POST /reload} and
 * {@code GET /eval} should be registered and reachable.
 *
 * <p>Only the server execution path is exercised here ({@code GameServer.server} is true, so the
 * endpoints run directly on the HTTP dispatcher thread). The client {@code MainThreadQueue} path
 * has no headless harness.
 */
@ExtendWith(ServerExtension.class)
class HotReloadEndpointsLiveTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE_URL = "http://localhost:" + ServerExtension.TEST_HTTP_PORT;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    void reloadRunsLuaFromRequestBody() throws Exception {
        HttpResponse<String> response = postReload("return \"hello from hotreload\"");

        Assertions.assertEquals(200, response.statusCode(), () -> "body: " + response.body());
        Assertions.assertTrue(
                response.body().contains("hello from hotreload"),
                "expected the Lua return value echoed back, got: " + response.body());
    }

    @Test
    void reloadRejectsEmptyBody() throws Exception {
        HttpResponse<String> response = postReload("");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(
                response.body().contains("missing Lua source"),
                "expected missing-body message, got: " + response.body());
    }

    @Test
    void reloadReportsLuaRuntimeError() throws Exception {
        HttpResponse<String> response = postReload("return definitely_not_a_function()");

        // The handler catches the Lua error and returns it with a 200 + ERROR: prefix.
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertTrue(
                response.body().startsWith("ERROR:"),
                "expected an ERROR: response for a failing Lua chunk, got: " + response.body());
    }

    @Test
    void evalLoadsAndRunsCompiledScript() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(
                compiler != null, "No system Java compiler (running on a JRE) — skipping /eval");

        Path classesDir = ServerExtension.getEvalClassesDir();
        Assertions.assertNotNull(classesDir, "ServerExtension did not configure eval classes dir");

        Path srcDir = Files.createTempDirectory("storm-hotreload-eval-src");
        Path srcFile = srcDir.resolve("EvalScript.java");
        Files.writeString(
                srcFile,
                "public class EvalScript {\n"
                        + "    public static Object run() {\n"
                        + "        return \"eval-ok-42\";\n"
                        + "    }\n"
                        + "}\n");

        int rc = compiler.run(null, null, null, "-d", classesDir.toString(), srcFile.toString());
        Assertions.assertEquals(0, rc, "EvalScript.java failed to compile");
        Assertions.assertTrue(
                Files.exists(classesDir.resolve("EvalScript.class")),
                "EvalScript.class was not produced in " + classesDir);

        HttpResponse<String> response = get("/eval");

        Assertions.assertEquals(200, response.statusCode(), () -> "body: " + response.body());
        Assertions.assertEquals("eval-ok-42", response.body());
    }

    private HttpResponse<String> postReload(String luaSource) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/reload"))
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(luaSource))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
