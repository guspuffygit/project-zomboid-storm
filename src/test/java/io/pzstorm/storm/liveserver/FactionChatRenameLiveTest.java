package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves {@link io.pzstorm.storm.patch.fixes.FactionChangeTitleChatPatch} keeps the faction chat
 * under the faction's current name across a rename. A real client creates a faction and renames it
 * over the wire; vanilla would leave the chat keyed by the old name.
 */
@ExtendWith(ServerExtension.class)
class FactionChatRenameLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROPAGATION_TIMEOUT = Duration.ofSeconds(10);

    private static final String USERNAME = "FactionOwner";
    private static final String PASSWORD = "passFaction";
    private static final String OLD_TITLE = "StormFacOld";
    private static final String NEW_TITLE = "StormFacNew";

    private static LiveServerClient client;

    @BeforeAll
    static void setup() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(USERNAME);
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void renamedFactionKeepsItsChatUnderTheNewName() throws Exception {
        client = new LiveServerClient(USERNAME, PASSWORD);
        client.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(client.isFullyConnected(), "client did not finish login handshake");

        disbandIfPresent(OLD_TITLE);
        disbandIfPresent(NEW_TITLE);
        try {
            createAndRename();
        } finally {
            disbandIfPresent(NEW_TITLE);
            disbandIfPresent(OLD_TITLE);
        }
    }

    /**
     * The test world persists across runs, and vanilla refuses a create from a player who already
     * owns a faction.
     */
    private static void disbandIfPresent(String title) throws Exception {
        if (!state(title).contains("faction=true")) return;
        client.sendFactionDisband(title);
        String gone = awaitState(title, "faction=false");
        Assertions.assertTrue(
                gone.contains("faction=false chat=false"),
                "leftover faction was not disbanded: " + gone);
    }

    private static void createAndRename() throws Exception {
        client.sendFactionCreate(OLD_TITLE, USERNAME);
        String created = awaitState(OLD_TITLE, "faction=true chat=true members=1");
        Assertions.assertTrue(
                created.contains("faction=true chat=true members=1"),
                "faction create did not produce a chat holding its owner: " + created);

        client.sendFactionChangeTitle(OLD_TITLE, NEW_TITLE);
        String renamed = awaitState(NEW_TITLE, "faction=true chat=true");
        Assertions.assertTrue(
                renamed.contains("faction=true chat=true members=1"),
                "renamed faction has no chat under its new name: " + renamed);

        String old = state(OLD_TITLE);
        Assertions.assertTrue(
                old.contains("faction=false chat=false"),
                "a chat is still keyed by the old faction name: " + old);
    }

    private static String awaitState(String name, String expected) throws Exception {
        Instant deadline = Instant.now().plus(PROPAGATION_TIMEOUT);
        String last = state(name);
        while (!last.contains(expected) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            last = state(name);
        }
        return last;
    }

    private static String state(String name) throws Exception {
        String result =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestfactionchatstate " + name, "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(result, "stormtestfactionchatstate produced no output");
        Assertions.assertFalse(result.contains("RESULT ERROR"), "state command failed: " + result);
        return result;
    }
}
