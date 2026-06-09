package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.patch.networking.ReceiveSandboxOptionsPatch;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test for {@link ReceiveSandboxOptionsPatch}: an admin-UI-style sandbox-options push
 * must propagate to the {@code storm_*} Prometheus gauges without a restart.
 *
 * <p>The test does the network round-trip by hand: it asks the server (via {@code /eval}) to mutate
 * {@code Storm.ZombieCullThreshold} to a known value, serialize the sandbox to a {@code
 * ByteBuffer}, mutate the option AWAY from that value (so we can tell whether {@code
 * receiveSandboxOptions} really reloaded), and then call the patched {@code
 * GameServer.receiveSandboxOptions} via reflection. Vanilla path:
 *
 * <pre>
 *   receiveSandboxOptions: load(buffer) -&gt; applySettings -&gt; toLua -&gt; saveServerLuaFile
 *                            -&gt; [advice] StormPerformanceSandboxApplier.applyAll()
 * </pre>
 *
 * <p>Afterwards {@code /metrics} is scraped and the {@code storm_zombie_cull_threshold} gauge must
 * match the target value. If the patch is missing or the advice doesn't fire, the gauge stays at
 * whatever was set just before {@code receiveSandboxOptions} and the assertion catches it.
 */
@ExtendWith(ServerExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SandboxOptionsHotReloadLiveTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String STORM_HTTP_URL =
            "http://localhost:" + ServerExtension.TEST_HTTP_PORT;
    private static final String METRICS_URL =
            "http://localhost:" + ServerExtension.TEST_PROMETHEUS_PORT + "/metrics";

    private static final int TARGET_THRESHOLD = 1234;
    private static final int DECOY_THRESHOLD = 77;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    @Order(1)
    void patchAppliedAtBoot() throws IOException {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "ServerExtension did not configure Storm log path");
        Assertions.assertTrue(
                Files.exists(stormLog),
                "Expected Storm main.log at " + stormLog + " but it was not created");
        String contents = Files.readString(stormLog, StandardCharsets.UTF_8);

        String patchName = ReceiveSandboxOptionsPatch.class.getSimpleName();
        String successMarker = "Successfully applied transformer " + patchName;
        String failureMarker = "Failed to apply transformer " + patchName;

        Assertions.assertTrue(
                contents.contains(successMarker),
                () ->
                        "Storm main.log missing '"
                                + successMarker
                                + "'. zombie.network.GameServer was not loaded during boot, or the"
                                + " patch failed to install. See log: "
                                + stormLog);
        Assertions.assertFalse(
                contents.contains(failureMarker),
                () ->
                        "Storm main.log contains '"
                                + failureMarker
                                + "' — patch threw during transform. See log: "
                                + stormLog);
    }

    /**
     * Verifies the boot-time sandbox apply path: {@code stormtest_SandboxVars.lua} pre-populated by
     * {@link ServerExtension} sets {@code Storm.ServerFps = 20}, and by the time {@code /metrics}
     * is scrapable, all three subordinate gauges reflect that value rather than the vanilla
     * default.
     *
     * <p>This must run before {@link #sandboxChangePropagatesToPrometheusGauge()} — the hot-reload
     * path re-invokes {@code StormPerformanceSandboxApplier.applyAll()} which re-applies ServerFps
     * unconditionally, so a regression in the boot-only callback in {@code
     * ServerLockFpsConfig.applyServerLockFps} would be silently repaired before this test could
     * observe it.
     */
    @Test
    @Order(2)
    void fpsControllersAppliedAtBoot() throws Exception {
        HttpResponse<String> metricsResp = get(METRICS_URL);
        Assertions.assertEquals(
                200,
                metricsResp.statusCode(),
                () -> "/metrics request failed: " + metricsResp.body());

        double intervalSeconds =
                parsePrometheusGauge(metricsResp.body(), "storm_server_tick_interval_seconds");
        double lockFps = parsePrometheusGauge(metricsResp.body(), "storm_server_lock_fps");
        double physicsFps =
                parsePrometheusGauge(metricsResp.body(), "storm_iso_physics_server_fps");

        double expectedIntervalSeconds =
                Math.max(1L, Math.round(1000.0 / ServerExtension.TEST_SERVER_FPS)) / 1000.0;

        Assertions.assertEquals(
                expectedIntervalSeconds,
                intervalSeconds,
                1e-9,
                "storm_server_tick_interval_seconds did not reflect Storm.ServerFps="
                        + ServerExtension.TEST_SERVER_FPS
                        + " at boot. UpdateLimitFactory.create's tick limiter was never retuned —"
                        + " the boot callback in ServerLockFpsConfig.applyServerLockFps must not"
                        + " have fired, or applyServerFps's isLimiterReady gate stayed false.");
        Assertions.assertEquals(
                (double) ServerExtension.TEST_SERVER_FPS,
                lockFps,
                0.0,
                "storm_server_lock_fps did not reflect Storm.ServerFps="
                        + ServerExtension.TEST_SERVER_FPS
                        + " at boot. Either the boot callback in"
                        + " ServerLockFpsConfig.applyServerLockFps never fired, or the vanilla"
                        + " GameServer.main setLockFPS(10) call overwrote the sandbox value"
                        + " AFTER the callback re-applied it.");
        Assertions.assertEquals(
                (double) ServerExtension.TEST_SERVER_FPS,
                physicsFps,
                0.0,
                "storm_iso_physics_server_fps did not reflect Storm.ServerFps="
                        + ServerExtension.TEST_SERVER_FPS
                        + " at boot. The unified ServerFpsConfig.applyUnifiedFps must not have"
                        + " run; check StormPerformanceSandboxApplier.applyServerFps's"
                        + " isLimiterReady guard.");
    }

    @Test
    @Order(3)
    void sandboxChangePropagatesToPrometheusGauge() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(
                compiler != null, "No system Java compiler (running on a JRE) — skipping");

        Path classesDir = ServerExtension.getEvalClassesDir();
        Assertions.assertNotNull(classesDir, "ServerExtension did not configure eval classes dir");
        compileEvalScript(compiler, classesDir);

        HttpResponse<String> evalResp = get(STORM_HTTP_URL + "/eval");
        Assertions.assertEquals(200, evalResp.statusCode(), () -> "body: " + evalResp.body());
        Assertions.assertFalse(
                evalResp.body().startsWith("ERROR:"),
                "EvalScript threw on server: " + evalResp.body());

        Assertions.assertTrue(
                evalResp.body().contains("sandbox=" + TARGET_THRESHOLD),
                "Sandbox option did not load back to target value. Eval said: " + evalResp.body());
        Assertions.assertTrue(
                evalResp.body().contains("config=" + TARGET_THRESHOLD),
                "Storm config did not track the sandbox change — advice did not fire or applier"
                        + " misread the option. Eval said: "
                        + evalResp.body());

        HttpResponse<String> metricsResp = get(METRICS_URL);
        Assertions.assertEquals(
                200,
                metricsResp.statusCode(),
                () -> "/metrics request failed: " + metricsResp.body());

        double gauge = parsePrometheusGauge(metricsResp.body(), "storm_zombie_cull_threshold");
        Assertions.assertEquals(
                (double) TARGET_THRESHOLD,
                gauge,
                0.0,
                "storm_zombie_cull_threshold gauge did not track the sandbox-options push."
                        + " Expected "
                        + TARGET_THRESHOLD
                        + " but got "
                        + gauge);
    }

    private static void compileEvalScript(JavaCompiler compiler, Path classesDir)
            throws IOException {
        Path srcDir = Files.createTempDirectory("storm-sandbox-eval-src");
        Path srcFile = srcDir.resolve("EvalScript.java");
        String src =
                "import io.pzstorm.storm.patch.performance.StormZombieCullConfig;\n"
                        + "import java.lang.reflect.Method;\n"
                        + "import java.nio.ByteBuffer;\n"
                        + "import zombie.SandboxOptions;\n"
                        + "import zombie.core.network.ByteBufferReader;\n"
                        + "import zombie.core.raknet.UdpConnection;\n"
                        + "import zombie.network.GameServer;\n"
                        + "\n"
                        + "public class EvalScript {\n"
                        + "    public static Object run() throws Exception {\n"
                        + "        SandboxOptions.IntegerSandboxOption opt =\n"
                        + "            (SandboxOptions.IntegerSandboxOption)\n"
                        + "                SandboxOptions.instance.getOptionByName(\"Storm.ZombieCullThreshold\");\n"
                        + "        if (opt == null) {\n"
                        + "            return \"ERROR: Storm.ZombieCullThreshold not registered\";\n"
                        + "        }\n"
                        + "\n"
                        + "        opt.setValue("
                        + TARGET_THRESHOLD
                        + ");\n"
                        + "        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);\n"
                        + "        SandboxOptions.instance.save(buffer);\n"
                        + "        buffer.flip();\n"
                        + "\n"
                        + "        opt.setValue("
                        + DECOY_THRESHOLD
                        + ");\n"
                        + "        StormZombieCullConfig.setThreshold("
                        + DECOY_THRESHOLD
                        + ");\n"
                        + "\n"
                        + "        Method m = GameServer.class.getDeclaredMethod(\n"
                        + "            \"receiveSandboxOptions\",\n"
                        + "            ByteBufferReader.class,\n"
                        + "            UdpConnection.class,\n"
                        + "            short.class);\n"
                        + "        m.setAccessible(true);\n"
                        + "        m.invoke(null, new ByteBufferReader(buffer), null, (short) 0);\n"
                        + "\n"
                        + "        return \"sandbox=\" + opt.getValue()\n"
                        + "            + \" config=\" + StormZombieCullConfig.getThreshold();\n"
                        + "    }\n"
                        + "}\n";
        Files.writeString(srcFile, src);
        int rc = compiler.run(null, null, null, "-d", classesDir.toString(), srcFile.toString());
        Assertions.assertEquals(0, rc, "EvalScript.java failed to compile");
        Assertions.assertTrue(
                Files.exists(classesDir.resolve("EvalScript.class")),
                "EvalScript.class was not produced in " + classesDir);
    }

    private static double parsePrometheusGauge(String body, String name) {
        Pattern p =
                Pattern.compile(
                        "^" + Pattern.quote(name) + "(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)",
                        Pattern.MULTILINE);
        Matcher m = p.matcher(body);
        Assertions.assertTrue(
                m.find(),
                "gauge "
                        + name
                        + " not found in /metrics body. First 2000 chars:\n"
                        + body.substring(0, Math.min(2000, body.length())));
        return Double.parseDouble(m.group(1));
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
