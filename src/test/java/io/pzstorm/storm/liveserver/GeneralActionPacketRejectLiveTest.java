package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test that proves {@link io.pzstorm.storm.patch.fixes.GeneralActionPacketPatch} works
 * over the real network path used by a real Project Zomboid client when the user cancels a timed
 * action.
 *
 * <p>The vanilla bug: {@code GeneralActionPacket.setReject(byte)} only assigns {@code id} and
 * {@code state}, leaving the inherited {@code playerId} at its default. Without the patch, the
 * server-side {@code processServer} hands that bare packet to {@code ActionManager.stop}; {@link
 * io.pzstorm.storm.advice.actionmanager.StopAdvice} (which filters by {@code (id, playerOnlineId)})
 * cannot match the queued action because the packet's {@code playerOnlineId} is {@code 0}.
 *
 * <p><b>Why two clients are required:</b> the very first connection assigned by {@code GameServer}
 * lands in slot 0, so its {@code playerOnlineId} is {@code 0}. If we tested with that connection,
 * the queued action's {@code playerId.id} would also be {@code 0} and the bug-bare packet's {@code
 * playerId.id == 0} would match the queue trivially under {@code StopAdvice} — passing the test
 * even without the patch. To force a non-zero slot for the test sender, we connect a filler client
 * first (taking slot 0) and use a second connection (slot 1, online id 4) to send the Reject. The
 * seed assertion below {@code seededOnlineId != 0} fails fast if the slot allocation ever changes,
 * so the test cannot regress into a trivially-passing state.
 *
 * <p>Sequence:
 *
 * <ol>
 *   <li>Connect an in-process filler client first → server slot 0, online id 0
 *   <li>Spawn a subprocess test sender → server slot 1, online id 4 (independent RakNet peer; one
 *       JVM peer cannot hold two connections to the same {@code (host, port)})
 *   <li>Server-side: seed a queued {@code NetTimedAction} for the test sender's user with a known
 *       byte id (via {@code stormtestseedaction}); assert the seeded action's online id is non-zero
 *       so the test exercises the bug path
 *   <li>Test sender ships a real {@code GeneralActionPacket} with {@code TransactionState.Reject}
 *       and that byte id, built via vanilla {@code setReject(byte)} so the on-wire {@code playerId}
 *       is left at default — exactly what the real game emits
 *   <li>Server-side: poll {@code stormtestcountaction} until the queue empties (or fail)
 * </ol>
 *
 * <p>The test never invokes {@code ActionManager.stop} from the test side; cancellation must
 * propagate through {@code GeneralActionPacket.processServer} (intercepted by {@code
 * ProcessServerAdvice}) for the queue to drain.
 */
@ExtendWith(ServerExtension.class)
class GeneralActionPacketRejectLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REJECT_PROPAGATION_TIMEOUT = Duration.ofSeconds(5);
    private static final byte TEST_BYTE_ID = 77;
    private static final String FILLER_USERNAME = "rejectFiller";
    private static final String FILLER_PASSWORD = "passFiller";
    private static final String TEST_USERNAME = "rejectUser";
    private static final String TEST_PASSWORD = "passReject";

    private static LiveServerClient filler;
    private static LiveServerClientProcess testSender;

    @BeforeAll
    static void setup() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(FILLER_USERNAME);
        ServerExtension.createTestCharacter(TEST_USERNAME);
    }

    @AfterAll
    static void teardown() {
        if (testSender != null) testSender.close();
        if (filler != null) filler.close();
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void wireLevelRejectRemovesQueuedAction() throws Exception {
        filler = new LiveServerClient(FILLER_USERNAME, FILLER_PASSWORD);
        filler.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(filler.isFullyConnected(), "filler did not finish login handshake");

        testSender = LiveServerClientProcess.spawn(TEST_USERNAME);
        long testGuid =
                testSender.connect(
                        "127.0.0.1",
                        ServerExtension.TEST_RAKNET_PORT,
                        ServerExtension.TEST_SERVER_PASSWORD,
                        TEST_USERNAME,
                        TEST_PASSWORD,
                        CONNECT_TIMEOUT);
        Assertions.assertTrue(testGuid != 0L, "testSender did not report a valid server GUID");

        String seedResult =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestseedaction " + TEST_USERNAME + " " + TEST_BYTE_ID,
                        "RESULT",
                        COMMAND_TIMEOUT);
        Assertions.assertNotNull(seedResult, "stormtestseedaction produced no output");
        System.out.println("[test] seed output: " + seedResult);
        Assertions.assertFalse(
                seedResult.contains("RESULT ERROR"), "seed command failed: " + seedResult);

        int seededCount = parseField(seedResult, "count");
        Assertions.assertEquals(
                1, seededCount, "expected exactly one action queued after seed: " + seedResult);

        int seededOnlineId = parseField(seedResult, "onlineId");
        Assertions.assertNotEquals(
                0,
                seededOnlineId,
                "test sender landed on slot 0 (onlineId=0) — the bug-bare packet's playerId.id=0"
                        + " would match StopAdvice's filter trivially and the test would pass even"
                        + " without the patch. Slot allocation has changed; reorder the connections so"
                        + " the test sender is on a non-zero slot.");

        testSender.sendGeneralActionReject(TEST_BYTE_ID);
        System.out.println(
                "[test] sent GeneralActionPacket Reject id=" + TEST_BYTE_ID + " over the wire");

        Instant deadline = Instant.now().plus(REJECT_PROPAGATION_TIMEOUT);
        int finalCount = -1;
        String lastOutput = null;
        while (Instant.now().isBefore(deadline)) {
            String countResult =
                    ServerExtension.sendCommandAndAwaitOutput(
                            "stormtestcountaction " + TEST_BYTE_ID, "RESULT", COMMAND_TIMEOUT);
            Assertions.assertNotNull(countResult, "stormtestcountaction produced no output");
            Assertions.assertFalse(
                    countResult.contains("RESULT ERROR"), "count command failed: " + countResult);
            lastOutput = countResult;
            finalCount = parseField(countResult, "count");
            if (finalCount == 0) {
                break;
            }
            Thread.sleep(200);
        }

        Assertions.assertEquals(
                0,
                finalCount,
                "queued action with byte id "
                        + TEST_BYTE_ID
                        + " (onlineId="
                        + seededOnlineId
                        + ") was not removed by the wire-level reject within "
                        + REJECT_PROPAGATION_TIMEOUT
                        + "; last server response: "
                        + lastOutput);
    }

    private static int parseField(String resultLine, String fieldName) {
        Pattern p = Pattern.compile(fieldName + "=(\\d+)");
        Matcher m = p.matcher(resultLine);
        Assertions.assertTrue(m.find(), "could not parse " + fieldName + " from: " + resultLine);
        return Integer.parseInt(m.group(1));
    }
}
