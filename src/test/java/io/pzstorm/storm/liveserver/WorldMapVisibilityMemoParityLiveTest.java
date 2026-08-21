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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Multi-connection parity test for {@link io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo}.
 *
 * <p>The memo's only state that a single-client session can't exercise is the per-viewer switch
 * ({@code switchConnection} swapping {@code CONNECTION_MEMBERS} as the batch walks connection →
 * connection) and the cross-connection faction / safehouse / role matrix. Three real RakNet
 * connections are held here — one in-process {@link LiveServerClient} plus two {@link
 * LiveServerClientProcess} peers (RakNet allows one outbound connection per (host, port) per JVM).
 *
 * <p>All fixture mutation and the parity probe itself run on the server main thread through the
 * test-only {@code stormtestworldmapparity} console command, which for every (connection, player)
 * pair evaluates the woven {@code GameServer.shouldSendWorldMapPlayerPosition} once with the memo
 * inactive (vanilla body) and once inside a memo batch, then runs the real all-connections batch. A
 * scenario passes when the two answer sets are identical, every in-batch evaluation was served by
 * the memo, the memo never latched off, and the vanilla truth matrix matches the expected semantics
 * for that fixture.
 */
@ExtendWith(ServerExtension.class)
class WorldMapVisibilityMemoParityLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private static final String ALICE = "WmAlice";
    private static final String BOB = "WmBob";
    private static final String CAROL = "WmCarol";
    private static final String PASSWORD = "wmpass";
    private static final String[] FIXTURE_USERS = {ALICE, BOB, CAROL};

    private static final int MODE_NONE = 1;
    private static final int MODE_FACTION_ONLY = 2;
    private static final int MODE_FACTION_AND_VISIBLE_ONLY = 3;
    private static final int MODE_ALL = 4;

    private static LiveServerClient alice;
    private static LiveServerClientProcess bob;
    private static LiveServerClientProcess carol;

    @BeforeAll
    static void connectThreeClients() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter(ALICE);
        ServerExtension.createTestCharacter(BOB);
        ServerExtension.createTestCharacter(CAROL);

        alice = new LiveServerClient(ALICE, PASSWORD);
        alice.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);
        Assertions.assertTrue(alice.isFullyConnected(), "alice did not finish login handshake");

        bob = LiveServerClientProcess.spawn(BOB);
        Assertions.assertTrue(
                bob.connect(
                                "127.0.0.1",
                                ServerExtension.TEST_RAKNET_PORT,
                                ServerExtension.TEST_SERVER_PASSWORD,
                                BOB,
                                PASSWORD,
                                CONNECT_TIMEOUT)
                        != 0L,
                "bob did not report a valid server GUID");

        carol = LiveServerClientProcess.spawn(CAROL);
        Assertions.assertTrue(
                carol.connect(
                                "127.0.0.1",
                                ServerExtension.TEST_RAKNET_PORT,
                                ServerExtension.TEST_SERVER_PASSWORD,
                                CAROL,
                                PASSWORD,
                                CONNECT_TIMEOUT)
                        != 0L,
                "carol did not report a valid server GUID");
    }

    @AfterAll
    static void teardown() throws Exception {
        try {
            command("reset");
        } finally {
            if (carol != null) carol.close();
            if (bob != null) bob.close();
            if (alice != null) alice.close();
            LiveServerClient.shutdownSharedEngine();
        }
    }

    @BeforeEach
    void resetFixture() throws Exception {
        Parity reset = command("reset");
        Assertions.assertTrue(reset.raw.contains("RESULT RESET"), "reset failed: " + reset.raw);
    }

    @Test
    void noneModeHidesEveryoneExceptFromSeeWorldMapRoles() throws Exception {
        command("mode " + MODE_NONE);
        Parity p = check();
        p.assertClean();
        p.assertTrueCount(0);

        command("role " + CAROL + " observer");
        p = check();
        p.assertClean();
        p.assertSees(CAROL, ALICE, true);
        p.assertSees(CAROL, BOB, true);
        p.assertSees(ALICE, CAROL, false);
        p.assertSees(BOB, ALICE, false);
        p.assertTrueCount(2);
    }

    @Test
    void factionOnlyModeFollowsFactionMembership() throws Exception {
        command("mode " + MODE_FACTION_ONLY);
        command("faction Wolves " + ALICE + " " + BOB);
        Parity p = check();
        p.assertClean();
        p.assertSees(ALICE, BOB, true);
        p.assertSees(BOB, ALICE, true);
        p.assertSees(ALICE, CAROL, false);
        p.assertSees(CAROL, ALICE, false);
        p.assertSees(CAROL, BOB, false);
        p.assertSees(ALICE, ALICE, false);
        p.assertTrueCount(2);

        // Tables are rebuilt per batch: growing the faction must show up on the next check.
        command("faction Bears " + CAROL);
        p = check();
        p.assertClean();
        p.assertSees(CAROL, ALICE, false);

        command("reset");
        command("mode " + MODE_FACTION_ONLY);
        command("faction Wolves " + ALICE + " " + BOB + " " + CAROL);
        p = check();
        p.assertClean();
        p.assertTrueCount(6);
    }

    @Test
    void factionOnlyModeFollowsSafehouseMembership() throws Exception {
        command("mode " + MODE_FACTION_ONLY);
        command("safehouse " + CAROL + " " + BOB);
        Parity p = check();
        p.assertClean();
        p.assertSees(BOB, CAROL, true);
        p.assertSees(CAROL, BOB, true);
        p.assertSees(ALICE, BOB, false);
        p.assertSees(BOB, ALICE, false);
        p.assertTrueCount(2);

        // Second safehouse, disjoint membership; plus faction and safehouse in the same batch.
        command("safehouse " + ALICE);
        command("faction Wolves " + ALICE + " " + CAROL);
        p = check();
        p.assertClean();
        p.assertSees(ALICE, CAROL, true);
        p.assertSees(CAROL, ALICE, true);
        p.assertSees(BOB, CAROL, true);
        p.assertSees(ALICE, BOB, false);
        p.assertTrueCount(4);
    }

    @Test
    void factionAndVisibleOnlyModeShowsEveryoneOnTheServer() throws Exception {
        // Server-side IsoPlayer.checkCanSeeClient(IsoPlayer) is unconditionally true.
        command("mode " + MODE_FACTION_AND_VISIBLE_ONLY);
        Parity p = check();
        p.assertClean();
        p.assertTrueCount(6);
    }

    /**
     * A connection that has not finished login has no role yet; vanilla NPEs on it (the main loop's
     * per-cycle catch swallows that). The memo must defer those pairs to vanilla — same throw —
     * instead of latching itself off for the rest of the session, and must be fully back once the
     * role exists.
     */
    @Test
    void roleLessConnectionDefersToVanillaWithoutLatchingOff() throws Exception {
        command("mode " + MODE_FACTION_ONLY);
        command("role " + BOB + " none");
        Parity p = check();
        Assertions.assertEquals(
                0, p.mismatches, "memo must throw exactly where vanilla throws: " + p.raw);
        Assertions.assertEquals(
                0, p.newFailures, "memo latched off on a role-less viewer: " + p.raw);
        Assertions.assertEquals(
                2, p.threw(BOB), "vanilla NPEs for bob's row on remote players: " + p.raw);
        Assertions.assertEquals(
                p.pairs - p.threw(BOB),
                p.memoEvals,
                "every pair except bob's deferred ones must be memo-served: " + p.raw);
        Assertions.assertEquals(1, p.batchThrew, "the real batch aborts like vanilla: " + p.raw);

        command("role " + BOB + " user");
        p = check();
        p.assertClean();
        p.assertTrueCount(0);
    }

    @Test
    void allModeShowsEveryone() throws Exception {
        command("mode " + MODE_ALL);
        Parity p = check();
        p.assertClean();
        p.assertTrueCount(6);
    }

    private static Parity check() throws Exception {
        return command("check");
    }

    private static Parity command(String args) throws Exception {
        String line =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormtestworldmapparity " + args, "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(line, "stormtestworldmapparity " + args + " produced no output");
        Assertions.assertFalse(
                line.contains("RESULT ERROR"), "stormtestworldmapparity " + args + ": " + line);
        return new Parity(line);
    }

    /** Parsed {@code RESULT PARITY ...} line (fields absent for non-check subcommands). */
    private static final class Parity {
        final String raw;
        final int connections;
        final int pairs;
        final int mismatches;
        final int vanillaTrue;
        final int memoTrue;
        final long vanillaEvals;
        final long memoEvals;
        final long batchMemoEvals;
        final long batchVanillaEvals;
        final long newFailures;
        final int batchThrew;
        final Map<String, Integer> matrix = new HashMap<>();

        Parity(String raw) {
            this.raw = raw;
            this.connections = intField("connections");
            this.pairs = intField("pairs");
            this.mismatches = intField("mismatches");
            this.vanillaTrue = intField("vanillaTrue");
            this.memoTrue = intField("memoTrue");
            this.vanillaEvals = intField("vanillaEvals");
            this.memoEvals = intField("memoEvals");
            this.batchMemoEvals = intField("batchMemoEvals");
            this.batchVanillaEvals = intField("batchVanillaEvals");
            this.newFailures = intField("newFailures");
            this.batchThrew = intField("batchThrew");
            Matcher m = Pattern.compile(" matrix=(\\S*)").matcher(raw);
            if (m.find()) {
                for (String entry : m.group(1).split(",")) {
                    int eq = entry.lastIndexOf('=');
                    if (eq > 0) {
                        matrix.put(
                                entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1)));
                    }
                }
            }
        }

        void assertClean() {
            // Other suite classes may leave a connection behind briefly; the fixture only needs
            // its own three to be present, and every count below is relative to `pairs`.
            Assertions.assertTrue(connections >= 3, "expected at least 3 connections: " + raw);
            Assertions.assertTrue(pairs >= 9, "expected at least a 3x3 matrix: " + raw);
            for (String viewer : FIXTURE_USERS) {
                for (String target : FIXTURE_USERS) {
                    Assertions.assertTrue(
                            matrix.containsKey(viewer + ">" + target),
                            "missing matrix entry " + viewer + ">" + target + ": " + raw);
                }
            }
            Assertions.assertEquals(0, mismatches, "memo disagrees with vanilla: " + raw);
            Assertions.assertEquals(vanillaTrue, memoTrue, raw);
            Assertions.assertEquals(
                    pairs, vanillaEvals, "vanilla pass must bypass the memo: " + raw);
            Assertions.assertEquals(pairs, memoEvals, "memo pass must answer every pair: " + raw);
            Assertions.assertEquals(
                    pairs, batchMemoEvals, "real batch must be served by the memo: " + raw);
            Assertions.assertEquals(
                    0, batchVanillaEvals, "real batch fell back to vanilla: " + raw);
            Assertions.assertEquals(0, newFailures, "memo latched off: " + raw);
        }

        /** Number of entries in {@code viewer}'s row (fixture targets only) where vanilla threw. */
        int threw(String viewer) {
            int count = 0;
            for (String target : FIXTURE_USERS) {
                Integer value = matrix.get(viewer + ">" + target);
                if (value != null && value == 2) {
                    count++;
                }
            }
            return count;
        }

        /** Number of true entries among the fixture users only. */
        void assertTrueCount(int expected) {
            int count = 0;
            for (String viewer : FIXTURE_USERS) {
                for (String target : FIXTURE_USERS) {
                    Integer value = matrix.get(viewer + ">" + target);
                    if (value != null && value == 1) {
                        count++;
                    }
                }
            }
            Assertions.assertEquals(expected, count, "true entries among fixture users: " + raw);
        }

        void assertSees(String viewer, String target, boolean expected) {
            Integer value = matrix.get(viewer + ">" + target);
            Assertions.assertNotNull(
                    value, "no matrix entry for " + viewer + ">" + target + ": " + raw);
            Assertions.assertEquals(
                    expected ? 1 : 0,
                    value,
                    viewer + " sees " + target + " expected " + expected + ": " + raw);
        }

        private int intField(String name) {
            Matcher m = Pattern.compile(" " + name + "=(-?\\d+)").matcher(raw);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }
    }
}
