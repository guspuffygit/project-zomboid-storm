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
 * End-to-end test that proves {@link io.pzstorm.storm.patch.fixes.ChatServerProcessWhisperPatch}
 * (driven by {@link io.pzstorm.storm.advice.whisperchatfix.ChatServerWhisperAdvice}) makes
 * whisper-chat creation case-insensitive and stores canonical usernames in the resulting {@code
 * WhisperChat}.
 *
 * <p>Vanilla bug: {@code ChatServer.processPlayerStartWhisperChatPacket} resolves the destination
 * name via {@code ChatUtility.findPlayer} → {@code GameServer.getPlayerByUserName}, which uses
 * {@code String.equals} on the username. Typing {@code /whisper alice hi} when the player is
 * registered as {@code Alice} resolves to {@code null} and the server replies with {@code
 * PlayerNotFound}; the dest client never sees a {@code WhisperChat}. Even if the lookup succeeds,
 * the vanilla code stores the as-typed names on the {@code WhisperChat}, so the dest client's
 * {@code WhisperChat.init()} (which uses {@code .equals}) bails on a case mismatch.
 *
 * <p>Fix: {@link io.pzstorm.storm.advice.whisperchatfix.ChatServerWhisperAdvice} replaces the body
 * with a case-insensitive lookup ({@code getPlayerByUserNameForCommand}) and constructs the {@code
 * WhisperChat} using the canonical usernames from {@code IsoPlayer.getUsername()}.
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

    private static final String DEST_USERNAME = "WhisperBob";
    private static final String DEST_PASSWORD = "passBob";
    private static final String AUTHOR_USERNAME = "WhisperAlice";
    private static final String AUTHOR_PASSWORD = "passAlice";

    private static LiveServerClient destClient;
    private static LiveServerClientProcess authorClient;

    @BeforeAll
    static void setup() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(DEST_USERNAME);
        ServerExtension.createTestCharacter(AUTHOR_USERNAME);
    }

    @AfterAll
    static void teardown() {
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
