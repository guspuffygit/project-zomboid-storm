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
 * End-to-end test that proves {@link io.pzstorm.storm.patch.fixes.GameServerStartPMChatPatch}
 * (driven by {@link io.pzstorm.storm.advice.whisperchatfix.GameServerStartPMChatAdvice}) makes
 * whisper-chat creation case-insensitive and stores canonical usernames in the resulting {@code
 * WhisperChat}.
 *
 * <p>Vanilla bug: {@code GameServer.receivePlayerStartPMChat} delegates to {@code
 * ChatServer.processPlayerStartWhisperChatPacket}, which trusts both client-supplied names. The
 * author name can be the stale {@code "Bob"} default (silent server-side throw, 10s client
 * timeout), and the destination is resolved via {@code ChatUtility.findPlayer} → {@code
 * GameServer.getPlayerByUserName}, which uses {@code String.equals} on the username. Typing {@code
 * /whisper alice hi} when the player is registered as {@code Alice} resolves to {@code null} and
 * the server replies with {@code PlayerNotFound}. Even if the lookup succeeds, the vanilla code
 * stores the as-typed names on the {@code WhisperChat}, so the dest client's {@code
 * WhisperChat.init()} (which uses {@code .equals}) bails on a case mismatch.
 *
 * <p>Fix: {@link io.pzstorm.storm.advice.whisperchatfix.GameServerStartPMChatAdvice} replaces the
 * packet handler: the author is resolved from the delivering {@code UdpConnection} (the
 * client-supplied name is only used to pick among splitscreen players), the destination via a
 * case-insensitive lookup, and the {@code WhisperChat} is constructed using the canonical usernames
 * from {@code IsoPlayer.getUsername()}.
 *
 * <p>This test verifies the fix server-side. It does <i>not</i> attempt to drive a real client
 * receiving a {@code WhisperChat} (which would require a second JVM with a running game render
 * loop) — instead it inspects {@code ChatServer.chats} via {@code stormtestwhisperchatstate} after
 * sending a raw {@code PlayerStartPMChat} packet with a mixed-case destination name. With the patch
 * active, the stored {@code player1}/{@code player2} must be the canonical (registered) usernames,
 * not the as-typed names.
 *
 * <p><b>Two clients required:</b> a whisper needs an existing destination player, so one client
 * acts as the destination and another as the author. RakNet allows only one outbound peer per
 * (host, port) per JVM, so the author runs in a {@link LiveServerClientProcess} subprocess.
 */
@ExtendWith(ServerExtension.class)
class WhisperChatCaseInsensitiveLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WHISPER_PROPAGATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BYSTANDER_DISCONNECT_TIMEOUT = Duration.ofSeconds(20);

    private static final String DEST_USERNAME = "WhisperBob";
    private static final String DEST_PASSWORD = "passBob";
    private static final String AUTHOR_USERNAME = "WhisperAlice";
    private static final String AUTHOR_PASSWORD = "passAlice";
    private static final String BYSTANDER_USERNAME = "WhisperCarol";
    private static final String BYSTANDER_PASSWORD = "passCarol";

    private static LiveServerClient destClient;
    private static LiveServerClientProcess authorClient;
    private static LiveServerClientProcess bystanderClient;

    @BeforeAll
    static void setup() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(DEST_USERNAME);
        ServerExtension.createTestCharacter(AUTHOR_USERNAME);
        ServerExtension.createTestCharacter(BYSTANDER_USERNAME);
    }

    @AfterAll
    static void teardown() {
        if (bystanderClient != null) bystanderClient.close();
        if (authorClient != null) authorClient.close();
        if (destClient != null) destClient.close();
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void mixedCaseDestinationCreatesWhisperChatWithCanonicalNames() throws Exception {
        destClient = new LiveServerClient(DEST_USERNAME, DEST_PASSWORD);
        destClient.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(
                destClient.isFullyConnected(), "destClient did not finish login handshake");

        authorClient = LiveServerClientProcess.spawn(AUTHOR_USERNAME);
        long authorGuid =
                authorClient.connect(
                        "127.0.0.1",
                        ServerExtension.TEST_RAKNET_PORT,
                        ServerExtension.TEST_SERVER_PASSWORD,
                        AUTHOR_USERNAME,
                        AUTHOR_PASSWORD,
                        CONNECT_TIMEOUT);
        Assertions.assertTrue(authorGuid != 0L, "authorClient did not report a valid server GUID");

        String baseline =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestwhisperchatstate", "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(baseline, "stormtestwhisperchatstate produced no baseline output");
        Assertions.assertFalse(
                baseline.contains("RESULT ERROR"),
                "baseline whisper-state command failed: " + baseline);
        int baselineCount = parseField(baseline, "count");
        System.out.println("[test] baseline whisper count=" + baselineCount);

        String typedAuthor = AUTHOR_USERNAME.toLowerCase();
        String typedDest = DEST_USERNAME.toLowerCase();
        authorClient.sendPlayerStartPMChat(typedAuthor, typedDest);
        System.out.println(
                "[test] sent PlayerStartPMChat author='"
                        + typedAuthor
                        + "' dest='"
                        + typedDest
                        + "'");

        Instant deadline = Instant.now().plus(WHISPER_PROPAGATION_TIMEOUT);
        String lastOutput = null;
        int finalCount = baselineCount;
        String latestPlayer1 = null;
        String latestPlayer2 = null;
        while (Instant.now().isBefore(deadline)) {
            String stateResult =
                    ServerExtension.sendCommandAndAwaitOutput(
                            "stormtestwhisperchatstate", "RESULT", COMMAND_TIMEOUT);
            Assertions.assertNotNull(stateResult, "stormtestwhisperchatstate produced no output");
            Assertions.assertFalse(
                    stateResult.contains("RESULT ERROR"),
                    "whisper-state command failed: " + stateResult);
            lastOutput = stateResult;
            finalCount = parseField(stateResult, "count");
            latestPlayer1 = parseStringField(stateResult, "player1");
            latestPlayer2 = parseStringField(stateResult, "player2");
            if (finalCount > baselineCount) {
                break;
            }
            Thread.sleep(200);
        }

        Assertions.assertTrue(
                finalCount > baselineCount,
                "no new WhisperChat was created within "
                        + WHISPER_PROPAGATION_TIMEOUT
                        + "; last server response: "
                        + lastOutput);

        Assertions.assertEquals(
                AUTHOR_USERNAME,
                latestPlayer1,
                "expected player1 to be the canonical author username '"
                        + AUTHOR_USERNAME
                        + "', got '"
                        + latestPlayer1
                        + "' — patch did not canonicalize. Full output: "
                        + lastOutput);
        Assertions.assertEquals(
                DEST_USERNAME,
                latestPlayer2,
                "expected player2 to be the canonical dest username '"
                        + DEST_USERNAME
                        + "', got '"
                        + latestPlayer2
                        + "' — patch did not canonicalize. Full output: "
                        + lastOutput);

        // Vanilla ChatServer.disconnectPlayer closes EVERY whisper chat server-wide on any
        // disconnect. Connect a third player who is in no whisper chat, disconnect them, and
        // assert the author↔dest chat survives (ChatServerDisconnectAdvice scopes the close to
        // chats the leaver was a member of). disconnectPlayer itself removes the leaver from
        // ChatServer.players, so a drop in chatPlayers proves the method ran — without it the
        // survival assertion would be vacuous if the server simply hadn't processed the
        // disconnect yet.
        bystanderClient = LiveServerClientProcess.spawn(BYSTANDER_USERNAME);
        long bystanderGuid =
                bystanderClient.connect(
                        "127.0.0.1",
                        ServerExtension.TEST_RAKNET_PORT,
                        ServerExtension.TEST_SERVER_PASSWORD,
                        BYSTANDER_USERNAME,
                        BYSTANDER_PASSWORD,
                        CONNECT_TIMEOUT);
        Assertions.assertTrue(
                bystanderGuid != 0L, "bystanderClient did not report a valid server GUID");

        String joinedState =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestwhisperchatstate", "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(joinedState, "stormtestwhisperchatstate produced no output");
        int playersWithBystander = parseField(joinedState, "chatPlayers");

        bystanderClient.close();
        bystanderClient = null;

        Instant disconnectDeadline = Instant.now().plus(BYSTANDER_DISCONNECT_TIMEOUT);
        String surviveOutput = null;
        boolean disconnectProcessed = false;
        while (Instant.now().isBefore(disconnectDeadline)) {
            surviveOutput =
                    ServerExtension.sendCommandAndAwaitOutput(
                            "stormtestwhisperchatstate", "RESULT", COMMAND_TIMEOUT);
            Assertions.assertNotNull(surviveOutput, "stormtestwhisperchatstate produced no output");
            Assertions.assertEquals(
                    finalCount,
                    parseField(surviveOutput, "count"),
                    "whisper chat between '"
                            + AUTHOR_USERNAME
                            + "' and '"
                            + DEST_USERNAME
                            + "' was closed by an unrelated player's disconnect — vanilla"
                            + " whisper-wipe bug is back. Full output: "
                            + surviveOutput);
            if (parseField(surviveOutput, "chatPlayers") < playersWithBystander) {
                disconnectProcessed = true;
                break;
            }
            Thread.sleep(500);
        }
        Assertions.assertTrue(
                disconnectProcessed,
                "server never processed the bystander disconnect within "
                        + BYSTANDER_DISCONNECT_TIMEOUT
                        + " — survival assertion is vacuous. Last output: "
                        + surviveOutput);
        System.out.println("[test] whisper chat survived bystander disconnect: " + surviveOutput);
    }

    private static int parseField(String resultLine, String fieldName) {
        Pattern p = Pattern.compile(fieldName + "=(-?\\d+)");
        Matcher m = p.matcher(resultLine);
        Assertions.assertTrue(m.find(), "could not parse " + fieldName + " from: " + resultLine);
        return Integer.parseInt(m.group(1));
    }

    private static String parseStringField(String resultLine, String fieldName) {
        Pattern p = Pattern.compile(fieldName + "=(\\S+)");
        Matcher m = p.matcher(resultLine);
        Assertions.assertTrue(m.find(), "could not parse " + fieldName + " from: " + resultLine);
        return m.group(1);
    }
}
