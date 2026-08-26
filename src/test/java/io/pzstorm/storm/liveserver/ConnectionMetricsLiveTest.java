package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.connection.ConnectionStage;
import io.pzstorm.storm.connection.RakNetConnectionCapConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that the connection-lifecycle metrics describe a real login, scraped from the
 * live server's own {@code /metrics} endpoint after a client reaches fully-connected.
 *
 * <p>Every assertion here covers a failure mode that is otherwise silent:
 *
 * <ul>
 *   <li><b>Stage series exist at all.</b> {@code StormConnectionStageMetrics} only registers when
 *       something loads the class; if the {@code ServerTickAdvice} call were dropped, {@code
 *       /metrics} would simply not mention connections and no test would notice.
 *   <li><b>Stages sum to slots used.</b> {@code ConnectionStage.classify} is a precedence chain — a
 *       stage that falls through, or a constant missing from {@code ALL}, loses connections
 *       silently. The sum is the invariant that catches it.
 *   <li><b>{@code storm_connected_clients} partitions the fully-connected population.</b> Storm
 *       detection is a two-source fallback (announced version, then the game-port TCP session), so
 *       a connection can fall out of both buckets or land in neither if either source starts
 *       answering for the wrong connection. The dashboard stacks the two series against {@code
 *       stage="fully_connected"}, where that shows up as an unexplained gap rather than an error.
 *   <li><b>{@code storm_connection_raknet_peers} is non-zero.</b> {@code
 *       RakNetPeerInterface.GetConnectionsNumber()} is declared {@code native} but called nowhere
 *       in vanilla Java, so the symbol may not be bound. Storm disables the gauge on the first
 *       throw, which would leave a permanently-flat series that looks like "no connections" on a
 *       dashboard. This asserts the native actually answers.
 *   <li><b>{@code storm_connection_events_total} is fed from both {@code ConnectionManager.log}
 *       overloads.</b> The RakNet accept goes through the {@code IConnection} overload on the
 *       network thread and the login packet through the same overload on the main thread, so a
 *       missing matcher or a thread-unsafe cache shows up as one of the two pairs being absent.
 * </ul>
 */
@ExtendWith(ServerExtension.class)
class ConnectionMetricsLiveTest implements IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(30);

    private static final String METRICS_URL =
            "http://localhost:" + ServerExtension.TEST_PROMETHEUS_PORT + "/metrics";

    private static final String USERNAME = "stormmetricsuser";

    private static LiveServerClient client;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @BeforeAll
    static void initNativesAndUser() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(USERNAME);
    }

    @AfterAll
    static void shutdownClient() {
        if (client != null) {
            client.close();
        }
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void loginFunnelMetricsDescribeARealConnection() throws Exception {
        client = new LiveServerClient(USERNAME, "stormmetricspass");
        client.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(client.isFullyConnected(), "expected fully connected state");

        // The sampler runs from ServerTickAdvice, so the spawn lands in Prometheus a tick later.
        String body = awaitFullyConnectedSample();

        double slotsUsed = metric(body, "storm_connection_slots_used");
        double slotsMax = metric(body, "storm_connection_slots_max");
        double stageSum = 0.0;
        for (String stage : ConnectionStage.ALL) {
            stageSum += labelled(body, "storm_connections", "stage", stage);
            // Every stage series must exist even while empty, or rate()/stackplots gap.
            labelled(body, "storm_connection_stage_age_seconds_max", "stage", stage);
        }
        Assertions.assertEquals(
                slotsUsed,
                stageSum,
                0.0,
                "storm_connections stages must partition every connection — they sum to "
                        + stageSum
                        + " but storm_connection_slots_used is "
                        + slotsUsed
                        + ". A ConnectionStage.classify branch is falling through, or a stage"
                        + " constant is missing from ConnectionStage.ALL.");
        Assertions.assertTrue(
                labelled(body, "storm_connections", "stage", ConnectionStage.FULLY_CONNECTED)
                        >= 1.0,
                "the spawned test client must be counted in stage=\"fully_connected\"");

        double stormClients = labelled(body, "storm_connected_clients", "client", "storm");
        double vanillaClients = labelled(body, "storm_connected_clients", "client", "vanilla");
        Assertions.assertEquals(
                labelled(body, "storm_connections", "stage", ConnectionStage.FULLY_CONNECTED),
                stormClients + vanillaClients,
                0.0,
                "storm_connected_clients must partition the fully-connected population — the"
                        + " dashboard stacks the two series against stage=\"fully_connected\", so a"
                        + " connection counted in neither (or in both) reads as a phantom gap"
                        + " there. storm="
                        + stormClients
                        + " vanilla="
                        + vanillaClients);
        Assertions.assertTrue(
                vanillaClients >= 1.0,
                "the test client speaks raw RakNet — it never sends StormPlayers.hello and never"
                        + " opens the game-port TCP channel, so it must classify as vanilla."
                        + " Counting it as storm means StormPlayersHandler.versionOf is answering"
                        + " for a connection that announced nothing.");

        Assertions.assertTrue(
                slotsMax >= RakNetConnectionCapConfig.VANILLA_CAP,
                "storm_connection_slots_max ("
                        + slotsMax
                        + ") must be at least the vanilla cap — the test server boots with"
                        + " MaxPlayers=100");
        Assertions.assertEquals(
                RakNetConnectionCapConfig.VANILLA_CAP,
                metric(body, "storm_connection_cap_vanilla"),
                0.0,
                "storm_connection_cap_vanilla is the fixed baseline dashboards subtract from"
                        + " storm_connection_slots_max to show Storm's headroom");
        Assertions.assertEquals(
                0.0,
                metric(body, "storm_connection_cap_fallback"),
                0.0,
                "RakNet refused to start with the raised cap on this boot — the headroom is not"
                        + " actually in place; see storm/main.log");

        Assertions.assertEquals(
                ServerExtension.TEST_REAP_CONNECT_BUDGET_MS / 1000.0,
                metric(body, "storm_connection_reap_timeout_seconds"),
                0.0,
                "storm_connection_reap_timeout_seconds must report the -D override the test server"
                        + " booted with, not the compiled-in default");
        Assertions.assertEquals(
                ServerExtension.TEST_REAP_SWEEP_INTERVAL_MS / 1000.0,
                metric(body, "storm_connection_reap_sweep_interval_seconds"),
                0.0,
                "storm_connection_reap_sweep_interval_seconds must report the -D override");
        metric(body, "storm_connection_reap_age_seconds_max");

        Assertions.assertTrue(
                metric(body, "storm_connection_raknet_peers") >= 1.0,
                "storm_connection_raknet_peers is 0 while a client is connected —"
                        + " RakNetPeerInterface.GetConnectionsNumber() did not answer (it is"
                        + " declared native but unreferenced in vanilla Java, so the symbol may be"
                        + " missing). Check storm/main.log for the disable WARN; if the native is"
                        + " genuinely gone, drop the gauge rather than ship a flat series.");

        Assertions.assertTrue(
                labelled(
                                body,
                                "storm_connection_events_total",
                                "source",
                                "RakNet",
                                "event",
                                "new-incoming-connection")
                        >= 1.0,
                "the RakNet accept was not counted — ConnectionManagerLogPatch is not hooking the"
                        + " IConnection overload, or it is not installed on the server");
        Assertions.assertTrue(
                labelled(
                                body,
                                "storm_connection_events_total",
                                "source",
                                "receive-packet",
                                "event",
                                "login")
                        >= 1.0,
                "the Login packet was not counted — the connections log is reaching Prometheus for"
                        + " RakNet events but not for packet events");

        double loginCount = metric(body, "storm_connection_login_duration_seconds_count");
        Assertions.assertTrue(
                loginCount >= 1.0,
                "storm_connection_login_duration_seconds observed nothing — the sampler saw the"
                        + " connection spawned on its very first tick and skipped it, or"
                        + " SEEN_PENDING tracking is broken. count="
                        + loginCount);
    }

    private String awaitFullyConnectedSample() throws Exception {
        Instant deadline = Instant.now().plus(SAMPLE_TIMEOUT);
        String body = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response =
                    http.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(METRICS_URL))
                                    .timeout(TIMEOUT)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(
                    200, response.statusCode(), () -> "/metrics failed: " + response.body());
            body = response.body();
            if (findLabelled(body, "storm_connections", "stage", ConnectionStage.FULLY_CONNECTED)
                    >= 1.0) {
                return body;
            }
            Thread.sleep(500);
        }
        Assertions.fail(
                "storm_connections{stage=\"fully_connected\"} never reached 1 within "
                        + SAMPLE_TIMEOUT
                        + " despite the client being fully connected — StormConnectionStageMetrics"
                        + ".recordAll() is not being called from ServerTickAdvice. Body:\n"
                        + excerpt(body));
        return body;
    }

    /** Value of an unlabelled series, failing with the scrape body when the series is absent. */
    private static double metric(String body, String name) {
        Pattern pattern =
                Pattern.compile(
                        "^" + Pattern.quote(name) + "\\s+([0-9.eE+-]+)\\s*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(body);
        Assertions.assertTrue(
                matcher.find(),
                "metric "
                        + name
                        + " is absent from /metrics — its collector class was never loaded on the"
                        + " server. Body:\n"
                        + excerpt(body));
        return Double.parseDouble(matcher.group(1));
    }

    /** Value of a labelled series, failing with the scrape body when the series is absent. */
    private static double labelled(String body, String name, String... labelPairs) {
        double value = findLabelled(body, name, labelPairs);
        Assertions.assertTrue(
                value >= 0.0,
                "series "
                        + name
                        + describe(labelPairs)
                        + " is absent from /metrics. Body:\n"
                        + excerpt(body));
        return value;
    }

    /**
     * Value of a labelled series, or {@code -1} when absent.
     *
     * <p>Labels are matched as a parsed set rather than by substring because {@code client_java}
     * emits label names in alphabetical order, not in {@code labelNames(...)} order — {@code
     * event=} precedes {@code source=} on the wire.
     */
    private static double findLabelled(String body, String name, String... labelPairs) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            wanted.put(labelPairs[i], labelPairs[i + 1]);
        }
        Pattern linePattern =
                Pattern.compile(
                        "^" + Pattern.quote(name) + "\\{([^}]*)}\\s+([0-9.eE+-]+)\\s*$",
                        Pattern.MULTILINE);
        Matcher lines = linePattern.matcher(body);
        while (lines.find()) {
            if (labelsMatch(lines.group(1), wanted)) {
                return Double.parseDouble(lines.group(2));
            }
        }
        return -1.0;
    }

    private static boolean labelsMatch(String labelBlock, Map<String, String> wanted) {
        Matcher labels =
                Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"").matcher(labelBlock);
        Map<String, String> present = new LinkedHashMap<>();
        while (labels.find()) {
            present.put(labels.group(1), labels.group(2));
        }
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            if (!entry.getValue().equals(present.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(String... labelPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(labelPairs[i]).append("=\"").append(labelPairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "<no scrape body>";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("storm_connection")) {
                sb.append(line).append('\n');
            }
        }
        return sb.length() > 0 ? sb.toString() : body.substring(0, Math.min(2000, body.length()));
    }
}
