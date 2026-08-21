package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Live parity test for {@code StormVehicleSoundRelevance} (the per-tick hoist of the vehicle-sound
 * audible-radius predicates) and {@code StormVehicleAlphaCheckSkip} (the server-side skip of {@code
 * BaseVehicle.couldSeeIntersectedSquare}).
 *
 * <p>One real RakNet connection is held so {@code GameServer.udpEngine.connections} and the
 * vehicleNetworkSound {@code Manager} both have a live entry. The test-only {@code
 * stormtestvehiclesound} console command spawns a vehicle at the player, flips engines on/off, and
 * for every (connection, vehicle) pair compares vanilla {@code Connection.isRelevant} with the
 * snapshot answer — all on the server main thread. It also reports whether the real per-tick {@code
 * Manager.update()} is being served from the snapshot (the {@code fastTotal} counter grows between
 * checks) and whether the alpha-check advice skipped a reflective probe call.
 */
@ExtendWith(ServerExtension.class)
class VehicleSoundRelevanceParityLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER = "VsAlice";
    private static final String PASSWORD = "vspass";

    private static LiveServerClient alice;

    @BeforeAll
    static void connectClient() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(USER);
        alice = new LiveServerClient(USER, PASSWORD);
        alice.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(alice.isFullyConnected(), "client did not finish login handshake");
        command("sound on");
        command("alpha on");
        spawnVehicleAtPlayer();
    }

    /** The player's chunk may still be hydrating right after login; retry the spawn briefly. */
    private static void spawnVehicleAtPlayer() throws Exception {
        String last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            last =
                    ServerExtension.sendCommandAndAwaitOutput(
                            "stormtestvehiclesound spawn " + USER, "RESULT", COMMAND_TIMEOUT);
            if (last != null && !last.contains("RESULT ERROR")) {
                Result spawn = new Result(last);
                Assertions.assertTrue(spawn.num("vehicles") >= 1, spawn.raw);
                return;
            }
            Thread.sleep(2000);
        }
        Assertions.fail("could not spawn a vehicle at " + USER + ": " + last);
    }

    @AfterAll
    static void disconnectClient() throws Exception {
        try {
            command("engine off");
            command("sound on");
            command("alpha on");
        } finally {
            if (alice != null) {
                alice.close();
            }
            LiveServerClient.shutdownSharedEngine();
        }
    }

    @Test
    void snapshotMatchesVanillaWithEnginesRunning() throws Exception {
        Result engine = command("engine on");
        Assertions.assertTrue(
                engine.num("notIdle") >= 1, "engineDoRunning left no vehicle noisy: " + engine.raw);

        Result check = command("check");
        assertParity(check);
        Assertions.assertTrue(check.num("noisy") >= 1, check.raw);
        Assertions.assertTrue(
                check.num("checked") >= 1, "no Manager connection for the client: " + check.raw);
        Assertions.assertEquals(check.num("checked"), check.num("fastFilled"), check.raw);
        Assertions.assertEquals(1, check.num("alphaSkipped"), check.raw);
        Assertions.assertEquals("false", check.str("alphaResult"), check.raw);

        Thread.sleep(1000);
        Result later = command("check");
        assertParity(later);
        Assertions.assertTrue(
                later.num("fastTotal") > check.num("fastTotal") + later.num("fastFilled"),
                "real Manager.update() ticks were not served from the snapshot: "
                        + check.raw
                        + " -> "
                        + later.raw);
    }

    @Test
    void snapshotMatchesVanillaWithEnginesOff() throws Exception {
        command("engine off");
        Result check = command("check");
        assertParity(check);
        Assertions.assertEquals(check.num("checked"), check.num("fastFilled"), check.raw);
    }

    @Test
    void killSwitchesRestoreVanillaPaths() throws Exception {
        command("sound off");
        command("alpha off");
        try {
            Result check = command("check");
            Assertions.assertEquals(0, check.num("fastFilled"), check.raw);
            Assertions.assertEquals(0, check.num("alphaSkipped"), check.raw);
            Assertions.assertEquals("false", check.str("soundEnabled"), check.raw);
            Assertions.assertEquals("false", check.str("alphaEnabled"), check.raw);
            long vanillaBefore = check.num("vanillaTotal");
            Thread.sleep(1000);
            Result later = command("check");
            Assertions.assertTrue(
                    later.num("vanillaTotal") > vanillaBefore + later.num("checked"),
                    "disabled fast path still answered Manager.update(): " + later.raw);
        } finally {
            command("sound on");
            command("alpha on");
        }
        Result restored = command("check");
        assertParity(restored);
        Assertions.assertEquals(restored.num("checked"), restored.num("fastFilled"), restored.raw);
        Assertions.assertEquals(1, restored.num("alphaSkipped"), restored.raw);
    }

    private static void assertParity(Result check) {
        Assertions.assertEquals(0, check.num("mismatches"), check.raw);
        Assertions.assertEquals(0, check.num("newFailures"), check.raw);
        Assertions.assertEquals("false", check.str("soundFailed"), check.raw);
        Assertions.assertEquals(0, check.num("nullConnections"), check.raw);
    }

    private static Result command(String args) throws Exception {
        String line =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestvehiclesound " + args, "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(line, "stormtestvehiclesound " + args + " produced no output");
        Assertions.assertFalse(
                line.contains("RESULT ERROR"), "stormtestvehiclesound " + args + ": " + line);
        return new Result(line);
    }

    /** Parsed {@code RESULT ... key=value ...} line. */
    private static final class Result {
        private static final Pattern FIELD = Pattern.compile("(\\w+)=(\\S+)");
        final String raw;
        final Map<String, String> fields = new HashMap<>();

        Result(String raw) {
            this.raw = raw;
            Matcher m = FIELD.matcher(raw.substring(raw.indexOf("RESULT")));
            while (m.find()) {
                fields.put(m.group(1), m.group(2));
            }
        }

        long num(String key) {
            String v = fields.get(key);
            Assertions.assertNotNull(v, "missing field " + key + " in: " + raw);
            return Long.parseLong(v);
        }

        String str(String key) {
            String v = fields.get(key);
            Assertions.assertNotNull(v, "missing field " + key + " in: " + raw);
            return v;
        }
    }
}
