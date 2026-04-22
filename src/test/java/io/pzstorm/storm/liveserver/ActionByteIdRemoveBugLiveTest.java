package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that vanilla {@code ActionManager.remove(byte, boolean)} filters by byte id alone: when
 * one player's action is removed, every other player's action that shares the same byte id is also
 * removed.
 *
 * <p>Two clients log in to a live dedicated server. A server-side console command ({@code
 * stormtestremovebug}) injects two fake {@link zombie.core.NetTimedAction} instances — one per
 * connected player — with the same byte id into the global {@code ActionManager} queue. It then
 * calls {@code ActionManager.remove(id, true)} (the same path triggered when any client cancels an
 * action) and reports how many actions had that id before and after the removal.
 *
 * <p>Expected (buggy) result: BEFORE=2, AFTER=0. Both actions are removed even though only one
 * player's action was targeted, because {@code remove()} filters by byte id alone.
 */
@ExtendWith(ServerExtension.class)
class ActionByteIdRemoveBugLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final byte COLLIDING_ID = 42;

    private static LiveServerClient clientA;
    private static LiveServerClientProcess clientB;

    @BeforeAll
    static void setup() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter("removeBugA");
        ServerExtension.createTestCharacter("removeBugB");
    }

    @AfterAll
    static void teardown() {
        if (clientA != null) clientA.close();
        if (clientB != null) clientB.close();
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void removeByByteIdDeletesBothPlayersActions() throws Exception {
        clientA = new LiveServerClient("removeBugA", "passA");
        clientA.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(clientA.isFullyConnected(), "clientA not fully connected");

        clientB = LiveServerClientProcess.spawn("removeBugB");
        clientB.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                "removeBugB",
                "passB",
                CONNECT_TIMEOUT);

        String result =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestremovebug removeBugA removeBugB " + COLLIDING_ID,
                        "RESULT",
                        COMMAND_TIMEOUT);
        Assertions.assertNotNull(result, "stormtestremovebug produced no output");
        System.out.println("[test] command output: " + result);

        Assertions.assertFalse(result.contains("RESULT ERROR"), "command failed: " + result);

        Pattern p = Pattern.compile("BEFORE=(\\d+)\\s+AFTER=(\\d+)");
        Matcher m = p.matcher(result);
        Assertions.assertTrue(m.find(), "could not parse result: " + result);

        int before = Integer.parseInt(m.group(1));
        int after = Integer.parseInt(m.group(2));

        Assertions.assertEquals(
                2, before, "expected two actions in queue before remove, got " + before);
        Assertions.assertEquals(1, after);
    }
}
