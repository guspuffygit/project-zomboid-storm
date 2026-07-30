package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.zombie.StormZombieTotalCap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end wiring check for {@code Storm.MaxTotalZombies}, the world-wide zombie ceiling.
 *
 * <p>All five layers have to line up for the option to do anything, and four of them fail silently:
 * a missing {@code media/sandbox-options.txt} entry leaves the option unregistered (the applier
 * logs a warn nobody reads), a missing applier call leaves {@link StormZombieTotalCap} at its
 * compiled-in default forever, and a missing {@code ServerTickAdvice} call leaves the cap
 * configured but never enforced. This test walks the live server through all of them via {@code
 * /eval}: it asserts the option is registered with the declared bounds, pushes a value through
 * {@code StormPerformanceSandboxApplier.applyAll()}, confirms the controller and the Prometheus
 * gauge both tracked it, and drives {@link StormZombieTotalCap#onServerTick()} directly with a cap
 * of 1 to prove the sweep survives contact with a real {@code IsoCell} / {@code UdpEngine}.
 *
 * <p>The eval restores the option and the controller to {@code 0} before returning — an idle test
 * server has no zombies to cull, but leaving a live cap behind would arm it for every later test in
 * the suite.
 */
@ExtendWith(ServerExtension.class)
class MaxTotalZombiesSandboxLiveTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String STORM_HTTP_URL =
            "http://localhost:" + ServerExtension.TEST_HTTP_PORT;
    private static final String METRICS_URL =
            "http://localhost:" + ServerExtension.TEST_PROMETHEUS_PORT + "/metrics";

    private static final int TARGET_CAP = 4321;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    void optionRegistersAppliesAndSweepsOnALiveServer() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(
                compiler != null, "No system Java compiler (running on a JRE) — skipping");

        HttpResponse<String> evalResp = postEval(compileEvalScript(compiler));
        Assertions.assertEquals(200, evalResp.statusCode(), () -> "body: " + evalResp.body());
        String body = evalResp.body();
        Assertions.assertFalse(body.startsWith("ERROR:"), "EvalScript threw on server: " + body);

        Assertions.assertTrue(
                body.contains("registered=true"),
                "Storm.MaxTotalZombies is not registered on SandboxOptions.instance — the option"
                        + " block is missing from the deployed media/sandbox-options.txt. Eval said: "
                        + body);
        Assertions.assertTrue(
                body.contains("bounds=" + StormZombieTotalCap.MIN + ".." + StormZombieTotalCap.MAX),
                "Declared option bounds drifted from StormZombieTotalCap.MIN/MAX, so the UI would"
                        + " let admins pick values the controller silently clamps. Eval said: "
                        + body);
        Assertions.assertTrue(
                body.contains("applied=" + TARGET_CAP),
                "StormPerformanceSandboxApplier.applyAll() did not push the option into"
                        + " StormZombieTotalCap — applyMaxTotalZombies() is not wired into"
                        + " applyAll(). Eval said: "
                        + body);
        Assertions.assertTrue(
                body.contains("sweptOk=true"),
                "StormZombieTotalCap.onServerTick() threw against the live world. Eval said: "
                        + body);
        Assertions.assertTrue(
                body.contains("restored=0"),
                "Eval failed to disarm the cap before returning. Eval said: " + body);

        HttpResponse<String> metricsResp = get(METRICS_URL);
        Assertions.assertEquals(
                200,
                metricsResp.statusCode(),
                () -> "/metrics request failed: " + metricsResp.body());

        Assertions.assertEquals(
                0.0,
                parsePrometheusMetric(metricsResp.body(), "storm_max_total_zombies"),
                0.0,
                "storm_max_total_zombies did not track the final setMaxTotal(0) — the gauge is not"
                        + " being pushed from StormZombieTotalCap.setMaxTotal.");
        parsePrometheusMetric(metricsResp.body(), "storm_zombies_total_cap_culled_total");
    }

    private static byte[] compileEvalScript(JavaCompiler compiler) throws IOException {
        Path srcDir = Files.createTempDirectory("storm-zcap-eval-src");
        Path classesDir = Files.createTempDirectory("storm-zcap-eval-classes");
        Path srcFile = srcDir.resolve("EvalScript.java");
        String src =
                "import io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier;\n"
                        + "import io.pzstorm.storm.zombie.StormZombieTotalCap;\n"
                        + "import zombie.SandboxOptions;\n"
                        + "\n"
                        + "public class EvalScript {\n"
                        + "    public static Object run() throws Exception {\n"
                        + "        SandboxOptions.SandboxOption raw =\n"
                        + "            SandboxOptions.instance.getOptionByName(\"Storm.MaxTotalZombies\");\n"
                        + "        if (!(raw instanceof SandboxOptions.IntegerSandboxOption)) {\n"
                        + "            return \"registered=false raw=\" + raw;\n"
                        + "        }\n"
                        + "        SandboxOptions.IntegerSandboxOption opt =\n"
                        + "            (SandboxOptions.IntegerSandboxOption) raw;\n"
                        + "\n"
                        + "        opt.setValue("
                        + TARGET_CAP
                        + ");\n"
                        + "        StormPerformanceSandboxApplier.applyAll();\n"
                        + "        int applied = StormZombieTotalCap.maxTotal();\n"
                        + "\n"
                        + "        boolean sweptOk = true;\n"
                        + "        String sweepError = \"\";\n"
                        + "        try {\n"
                        + "            StormZombieTotalCap.setMaxTotal(1);\n"
                        + "            StormZombieTotalCap.onServerTick();\n"
                        + "        } catch (Throwable t) {\n"
                        + "            sweptOk = false;\n"
                        + "            sweepError = \" sweepError=\" + t;\n"
                        + "        }\n"
                        + "\n"
                        + "        opt.setValue(0);\n"
                        + "        StormZombieTotalCap.setMaxTotal(0);\n"
                        + "\n"
                        + "        return \"registered=true\"\n"
                        + "            + \" bounds=\" + (int) opt.getMin() + \"..\" + (int) opt.getMax()\n"
                        + "            + \" applied=\" + applied\n"
                        + "            + \" sweptOk=\" + sweptOk\n"
                        + "            + \" restored=\" + StormZombieTotalCap.maxTotal()\n"
                        + "            + sweepError;\n"
                        + "    }\n"
                        + "}\n";
        Files.writeString(srcFile, src);
        int rc = compiler.run(null, null, null, "-d", classesDir.toString(), srcFile.toString());
        Assertions.assertEquals(0, rc, "EvalScript.java failed to compile");
        Path classFile = classesDir.resolve("EvalScript.class");
        Assertions.assertTrue(Files.exists(classFile), "EvalScript.class was not produced");
        return Files.readAllBytes(classFile);
    }

    private HttpResponse<String> postEval(byte[] body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(STORM_HTTP_URL + "/eval"))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/java-vm")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static double parsePrometheusMetric(String body, String name) {
        Pattern p =
                Pattern.compile(
                        "^" + Pattern.quote(name) + "(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)",
                        Pattern.MULTILINE);
        Matcher m = p.matcher(body);
        Assertions.assertTrue(
                m.find(),
                "metric "
                        + name
                        + " not found in /metrics body — the collector class was never loaded on"
                        + " the server. First 2000 chars:\n"
                        + body.substring(0, Math.min(2000, body.length())));
        return Double.parseDouble(m.group(1));
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
