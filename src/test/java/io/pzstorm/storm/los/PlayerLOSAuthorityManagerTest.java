package io.pzstorm.storm.los;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * Unit tests for {@link PlayerLOSAuthorityManager}'s solo/grouped state machine.
 *
 * <p>The manager is a singleton driven by side effects ({@code GameServer.server}, {@code
 * GameServer.Players}, and an internal state map), so each test resets all three. {@link IsoPlayer}
 * fixtures are built via {@link Unsafe#allocateInstance} — the manager only reads {@code getX/Y/Z}
 * and {@code getOnlineID}, so we never need a fully constructed character.
 */
class PlayerLOSAuthorityManagerTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    private boolean savedServerFlag;
    private Map<Short, ?> states;

    @BeforeEach
    void setUp() throws Exception {
        savedServerFlag = GameServer.server;
        GameServer.server = true;
        GameServer.Players.clear();
        states = managerStates();
        states.clear();
    }

    @AfterEach
    void tearDown() {
        GameServer.Players.clear();
        states.clear();
        GameServer.server = savedServerFlag;
    }

    // -------- tick() guard --------

    @Test
    void tickIsNoOpWhenServerFlagIsFalse() throws Exception {
        GameServer.server = false;
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        // No state was created → isSolo defaults to false.
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(states.isEmpty());
    }

    // -------- isSolo() lookup --------

    @Test
    void isSoloReturnsFalseForUnknownOnlineId() {
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 42));
    }

    // -------- initial state on first tick --------

    @Test
    void playerAloneIsSoloOnFirstTick() throws Exception {
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
    }

    @Test
    void playerWithNeighborAtSoloThresholdIsSoloOnFirstTick() throws Exception {
        // Distance == SOLO_THRESHOLD (256). Initial check is `>=`, so SOLO at the boundary.
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(
                newPlayer(
                        (short) 2, "bob", PlayerLOSAuthorityManager.SOLO_THRESHOLD_SQUARES, 0, 0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void playerWithNeighborJustInsideSoloThresholdIsGroupedOnFirstTick() throws Exception {
        // 1 square below SOLO_THRESHOLD: not >= threshold → GROUPED.
        float justInside = PlayerLOSAuthorityManager.SOLO_THRESHOLD_SQUARES - 1f;
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(newPlayer((short) 2, "bob", justInside, 0, 0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void playerWithNeighborAtGroupedThresholdIsGroupedOnFirstTick() throws Exception {
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(
                newPlayer(
                        (short) 2,
                        "bob",
                        PlayerLOSAuthorityManager.GROUPED_THRESHOLD_SQUARES,
                        0,
                        0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    // -------- hysteresis transitions --------

    @Test
    void soloPlayerTransitionsToGroupedWhenNeighborMovesWithinGroupedThreshold() throws Exception {
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob = newPlayer((short) 2, "bob", 1000, 0, 0);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        // Tick 1: far apart → both SOLO.
        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));

        // Move bob to GROUPED threshold (192). Boundary `<=` → GROUPED.
        setField(
                IsoMovingObject.class,
                bob,
                "x",
                PlayerLOSAuthorityManager.GROUPED_THRESHOLD_SQUARES);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void groupedPlayerTransitionsToSoloWhenNeighborMovesPastSoloThreshold() throws Exception {
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob = newPlayer((short) 2, "bob", 50, 0, 0);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        // Tick 1: close → both GROUPED.
        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));

        // Move bob to SOLO threshold (256). Boundary `>=` → SOLO.
        setField(IsoMovingObject.class, bob, "x", PlayerLOSAuthorityManager.SOLO_THRESHOLD_SQUARES);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void soloPlayerStaysSoloInsideHysteresisBand() throws Exception {
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob = newPlayer((short) 2, "bob", 1000, 0, 0);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));

        // 224 sits squarely between GROUPED (192) and SOLO (256) thresholds.
        setField(IsoMovingObject.class, bob, "x", 224f);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void groupedPlayerStaysGroupedInsideHysteresisBand() throws Exception {
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob = newPlayer((short) 2, "bob", 50, 0, 0);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));

        setField(IsoMovingObject.class, bob, "x", 224f);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    // -------- nearest-of-many --------

    @Test
    void nearestOtherDistanceUsesMinimumOfMultiplePlayers() throws Exception {
        // Alice: bob at 1000, carol at 50 → nearest = carol → GROUPED.
        // Bob:   alice at 1000, carol at 950 → nearest = carol at 950 → SOLO.
        // Carol: alice at 50, bob at 950 → nearest = alice → GROUPED.
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(newPlayer((short) 2, "bob", 1000, 0, 0));
        GameServer.Players.add(newPlayer((short) 3, "carol", 50, 0, 0));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 3));
    }

    // -------- 3D distance --------

    @Test
    void distanceCalculationIncludesZAxis() throws Exception {
        // Same x/y; z difference == SOLO_THRESHOLD → distance == SOLO_THRESHOLD → SOLO.
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob =
                newPlayer((short) 2, "bob", 0, 0, PlayerLOSAuthorityManager.SOLO_THRESHOLD_SQUARES);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));

        // Drop bob's z so 3D distance falls below GROUPED threshold → SOLO -> GROUPED.
        setField(
                IsoMovingObject.class,
                bob,
                "z",
                PlayerLOSAuthorityManager.GROUPED_THRESHOLD_SQUARES - 50f);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    @Test
    void distanceIsEuclideanAcrossAllAxes() throws Exception {
        // (0,0,0) to (3,4,12) → distance 13 (3-4-12 Pythagorean triple).
        // 13 << GROUPED threshold → both GROUPED.
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(newPlayer((short) 2, "bob", 3, 4, 12));

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
    }

    // -------- disconnect cleanup --------

    @Test
    void disconnectedPlayerStateIsCleanedUpAndAlonePlayerBecomesSolo() throws Exception {
        IsoPlayer alice = newPlayer((short) 1, "alice", 0, 0, 0);
        IsoPlayer bob = newPlayer((short) 2, "bob", 50, 0, 0);
        GameServer.Players.add(alice);
        GameServer.Players.add(bob);

        PlayerLOSAuthorityManager.INSTANCE.tick();
        assertTrue(states.containsKey((short) 1));
        assertTrue(states.containsKey((short) 2));

        // Bob disconnects.
        GameServer.Players.remove(bob);

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertTrue(states.containsKey((short) 1));
        assertFalse(states.containsKey((short) 2));
        assertFalse(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 2));
        // Alice is now alone → transitioned GROUPED -> SOLO.
        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
    }

    // -------- null tolerance --------

    @Test
    void nullEntriesInPlayersListAreSkipped() throws Exception {
        GameServer.Players.add(newPlayer((short) 1, "alice", 0, 0, 0));
        GameServer.Players.add(null);

        PlayerLOSAuthorityManager.INSTANCE.tick();

        assertTrue(PlayerLOSAuthorityManager.INSTANCE.isSolo((short) 1));
    }

    // -------- concurrent access --------

    @Test
    void concurrentIsSoloAndTickDoNotCorruptStateOrThrow() throws Exception {
        // Sanity check the defensive-future-proofing contract: many isSolo readers may run
        // alongside a single tick writer without NPE / ConcurrentModificationException / hang.
        int playerCount = 8;
        IsoPlayer[] players = new IsoPlayer[playerCount];
        for (int i = 0; i < playerCount; i++) {
            players[i] = newPlayer((short) (i + 1), "p" + i, i * 100f, 0, 0);
            GameServer.Players.add(players[i]);
        }

        int readerCount = 4;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);

        Thread[] readers = new Thread[readerCount];
        for (int t = 0; t < readerCount; t++) {
            readers[t] =
                    new Thread(
                            () -> {
                                try {
                                    ready.countDown();
                                    start.await();
                                    while (!stop.get()) {
                                        // Mix known and unknown ids to exercise both hit and miss
                                        // paths through the ConcurrentHashMap.
                                        for (short i = 1; i <= playerCount + 4; i++) {
                                            PlayerLOSAuthorityManager.INSTANCE.isSolo(i);
                                        }
                                    }
                                } catch (Throwable e) {
                                    failure.compareAndSet(null, e);
                                }
                            },
                            "LOS-reader-" + t);
            readers[t].setDaemon(true);
            readers[t].start();
        }

        Thread writer =
                new Thread(
                        () -> {
                            try {
                                ready.countDown();
                                start.await();
                                int iter = 0;
                                while (!stop.get()) {
                                    // Toggle one player's x between near and far so tick must
                                    // cross hysteresis thresholds and rewrite state.
                                    IsoPlayer p = players[iter % playerCount];
                                    float x = (iter % 2 == 0) ? 5f : 1000f;
                                    setField(IsoMovingObject.class, p, "x", x);
                                    PlayerLOSAuthorityManager.INSTANCE.tick();
                                    iter++;
                                }
                            } catch (Throwable e) {
                                failure.compareAndSet(null, e);
                            }
                        },
                        "LOS-writer");
        writer.setDaemon(true);
        writer.start();

        ready.await();
        start.countDown();
        Thread.sleep(250L);
        stop.set(true);

        writer.join(2_000L);
        for (Thread r : readers) {
            r.join(2_000L);
        }

        Throwable t = failure.get();
        if (t != null) {
            throw new AssertionError("Concurrent access failed", t);
        }

        // Final sanity: all players still have a state entry and isSolo is internally consistent.
        for (int i = 0; i < playerCount; i++) {
            short id = (short) (i + 1);
            assertTrue(
                    states.containsKey(id),
                    "expected state entry for onlineID=" + id + " after concurrent run");
        }
    }

    // -------- helpers --------

    private static IsoPlayer newPlayer(short onlineId, String username, float x, float y, float z)
            throws Exception {
        IsoPlayer p = (IsoPlayer) UNSAFE.allocateInstance(IsoPlayer.class);
        setField(IsoPlayer.class, p, "onlineId", onlineId);
        setField(IsoPlayer.class, p, "username", username);
        setField(IsoMovingObject.class, p, "x", x);
        setField(IsoMovingObject.class, p, "y", y);
        setField(IsoMovingObject.class, p, "z", z);
        return p;
    }

    private static void setField(Class<?> clazz, Object instance, String name, Object value)
            throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(instance, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Short, ?> managerStates() throws Exception {
        Field f = PlayerLOSAuthorityManager.class.getDeclaredField("states");
        f.setAccessible(true);
        return (Map<Short, ?>) f.get(PlayerLOSAuthorityManager.INSTANCE);
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe u = (Unsafe) f.get(null);
            assertNotNull(u);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Unable to acquire sun.misc.Unsafe", e);
        }
    }
}
