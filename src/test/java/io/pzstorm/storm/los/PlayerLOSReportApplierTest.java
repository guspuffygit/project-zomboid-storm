package io.pzstorm.storm.los;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.Stack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Unit tests for {@link PlayerLOSReportApplier}. The applier mutates an {@link IsoPlayer}'s
 * spottedList / lastSpotted / stat counters in place; tests check the resets, the selfSpotted path,
 * the resolution-miss path, and {@code resolve()} ID disambiguation.
 *
 * <p>The deep per-entity replay (lit zombie → numVisibleZombies++ with room/ghost checks,
 * TestZombieSpotPlayer dispatch) needs a populated IsoGridSquare graph and ServerLOS infra, so it
 * stays as integration validation rather than a unit test.
 */
class PlayerLOSReportApplierTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    private boolean savedServerFlag;

    @BeforeAll
    static void seedRandomizer() throws Exception {
        // ServerMap's clinit constructs an IsoObjectID, which calls Rand.Next on a randomizer that
        // is null in the test JVM — install a deterministic Random so the class loads.
        Field rand = RandAbstract.class.getDeclaredField("rand");
        rand.setAccessible(true);
        if (rand.get(RandStandard.INSTANCE) == null) {
            rand.set(RandStandard.INSTANCE, new Random(0L));
        }
    }

    @BeforeEach
    void setUp() {
        savedServerFlag = GameServer.server;
        GameServer.server = true;
        GameServer.IDToPlayerMap.clear();
        ServerMap.instance.zombieMap.clear();
    }

    @AfterEach
    void tearDown() {
        GameServer.IDToPlayerMap.clear();
        ServerMap.instance.zombieMap.clear();
        GameServer.server = savedServerFlag;
    }

    // -------- resolve() --------

    @Test
    void resolveReturnsPlayerWhenIdInPlayerMap() throws Exception {
        IsoPlayer alice = newPlayer((short) 5, 0, 0, 0);
        GameServer.IDToPlayerMap.put((short) 5, alice);

        assertSame(alice, PlayerLOSReportApplier.resolve((short) 5));
    }

    @Test
    void resolveFallsBackToZombieMap() throws Exception {
        IsoZombie z = newZombie((short) 7, 0, 0, 0);
        ServerMap.instance.zombieMap.put((short) 7, z);

        assertSame(z, PlayerLOSReportApplier.resolve((short) 7));
    }

    @Test
    void resolvePrefersPlayerOverZombieForSameId() throws Exception {
        IsoPlayer alice = newPlayer((short) 5, 0, 0, 0);
        IsoZombie z = newZombie((short) 5, 0, 0, 0);
        GameServer.IDToPlayerMap.put((short) 5, alice);
        ServerMap.instance.zombieMap.put((short) 5, z);

        assertSame(alice, PlayerLOSReportApplier.resolve((short) 5));
    }

    @Test
    void resolveReturnsNullWhenIdInNeitherMap() {
        assertNull(PlayerLOSReportApplier.resolve((short) 42));
    }

    // -------- apply() reset semantics --------

    @Test
    void applyResetsSpottedListAndCountersBeforeProcessingEntries() throws Exception {
        IsoPlayer player = newPlayerWithStateAndSquare((short) 1, 0, 0, 0);
        Stack<IsoMovingObject> spotted = player.getSpottedList();
        IsoZombie stale = newZombie((short) 99, 0, 0, 0);
        spotted.add(stale);
        Stats stats = player.getStats();
        stats.numVisibleZombies = 7;
        stats.numChasingZombies = 3;
        player.setNumSurvivorsInVicinity(5);

        PlayerLOSReportApplier.apply(player, emptyReport((short) 1));

        assertTrue(spotted.isEmpty());
        assertEquals(0, stats.numVisibleZombies);
        assertEquals(0, stats.numChasingZombies);
        assertEquals(3, getLastChasing(stats));
        assertEquals(0, player.getNumSurvivorsInVicinity());
    }

    @Test
    void applyReturnsEarlyWhenCurrentSquareIsNull() throws Exception {
        IsoPlayer player = newPlayer((short) 1, 0, 0, 0);
        setField(IsoGameCharacter.class, player, "stats", new Stats());
        setField(IsoPlayer.class, player, "spottedList", new Stack<IsoMovingObject>());
        setField(IsoPlayer.class, player, "lastSpotted", new Stack<IsoMovingObject>());
        player.getStats().numVisibleZombies = 7;
        Stack<IsoMovingObject> spotted = player.getSpottedList();
        spotted.add(newZombie((short) 99, 0, 0, 0));

        IsoZombie target = newZombie((short) 50, 0, 0, 0);
        ServerMap.instance.zombieMap.put((short) 50, target);

        PlayerLOSReportApplier.apply(
                player,
                reportWithEntries((short) 1, false, new short[] {50}, new boolean[] {true}));

        // Reset still happened, but the entry loop was skipped because square is null.
        assertTrue(spotted.isEmpty());
        assertEquals(0, player.getStats().numVisibleZombies);
    }

    // -------- selfSpotted --------

    @Test
    void applyAddsSelfWhenSelfSpotted() throws Exception {
        IsoPlayer player = newPlayerWithStateAndSquare((short) 1, 0, 0, 0);

        PlayerLOSReportApplier.apply(
                player,
                reportWithEntries(
                        /* playerOnlineID */ (short) 1,
                        /* selfSpotted */ true,
                        new short[0],
                        new boolean[0]));

        assertEquals(1, player.getSpottedList().size());
        assertSame(player, player.getSpottedList().get(0));
    }

    @Test
    void applyDoesNotAddSelfWhenSelfSpottedIsFalse() throws Exception {
        IsoPlayer player = newPlayerWithStateAndSquare((short) 1, 0, 0, 0);

        PlayerLOSReportApplier.apply(player, emptyReport((short) 1));

        assertTrue(player.getSpottedList().isEmpty());
    }

    // -------- resolution-miss bookkeeping --------

    @Test
    void applyIgnoresEntriesWithUnresolvedIds() throws Exception {
        IsoPlayer player = newPlayerWithStateAndSquare((short) 1, 0, 0, 0);

        PlayerLOSReportApplier.apply(
                player,
                reportWithEntries(
                        (short) 1,
                        false,
                        new short[] {77, 78, 79},
                        new boolean[] {true, true, true}));

        // None of the IDs resolve → nothing added to spottedList, no NPE, counters stay zeroed.
        assertTrue(player.getSpottedList().isEmpty());
        assertEquals(0, player.getStats().numVisibleZombies);
        assertEquals(0, player.getNumSurvivorsInVicinity());
    }

    @Test
    void applySkipsEntriesPointingAtSelf() throws Exception {
        IsoPlayer player = newPlayerWithStateAndSquare((short) 1, 0, 0, 0);
        GameServer.IDToPlayerMap.put((short) 1, player);

        PlayerLOSReportApplier.apply(
                player, reportWithEntries((short) 1, false, new short[] {1}, new boolean[] {true}));

        // Self filter is the loop body's `obj == player` check — selfSpotted = false means no add.
        assertTrue(player.getSpottedList().isEmpty());
    }

    // -------- helpers --------

    private static PlayerLOSReportCache.Report emptyReport(short playerOnlineID) {
        return new PlayerLOSReportCache.Report(
                playerOnlineID,
                0L,
                0L,
                System.currentTimeMillis(),
                false,
                false,
                new short[0],
                new boolean[0],
                new boolean[0]);
    }

    private static PlayerLOSReportCache.Report reportWithEntries(
            short playerOnlineID, boolean selfSpotted, short[] ids, boolean[] lit) {
        boolean[] couldSee = new boolean[ids.length];
        boolean[] canSee = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) {
            couldSee[i] = lit[i];
            canSee[i] = lit[i];
        }
        return new PlayerLOSReportCache.Report(
                playerOnlineID,
                0L,
                0L,
                System.currentTimeMillis(),
                selfSpotted,
                false,
                ids,
                couldSee,
                canSee);
    }

    private static IsoPlayer newPlayer(short onlineId, float x, float y, float z) throws Exception {
        IsoPlayer p = (IsoPlayer) UNSAFE.allocateInstance(IsoPlayer.class);
        setField(IsoPlayer.class, p, "onlineId", onlineId);
        setField(IsoMovingObject.class, p, "x", x);
        setField(IsoMovingObject.class, p, "y", y);
        setField(IsoMovingObject.class, p, "z", z);
        return p;
    }

    private static IsoPlayer newPlayerWithStateAndSquare(short onlineId, float x, float y, float z)
            throws Exception {
        IsoPlayer p = newPlayer(onlineId, x, y, z);
        setField(IsoGameCharacter.class, p, "stats", new Stats());
        setField(IsoPlayer.class, p, "spottedList", new Stack<IsoMovingObject>());
        setField(IsoPlayer.class, p, "lastSpotted", new Stack<IsoMovingObject>());
        IsoGridSquare square = (IsoGridSquare) UNSAFE.allocateInstance(IsoGridSquare.class);
        setField(IsoMovingObject.class, p, "current", square);
        return p;
    }

    private static IsoZombie newZombie(short onlineId, float x, float y, float z) throws Exception {
        IsoZombie zombie = (IsoZombie) UNSAFE.allocateInstance(IsoZombie.class);
        setField(IsoZombie.class, zombie, "onlineId", onlineId);
        setField(IsoMovingObject.class, zombie, "x", x);
        setField(IsoMovingObject.class, zombie, "y", y);
        setField(IsoMovingObject.class, zombie, "z", z);
        return zombie;
    }

    private static int getLastChasing(Stats stats) throws Exception {
        Field f = Stats.class.getDeclaredField("lastNumChasingZombies");
        f.setAccessible(true);
        return f.getInt(stats);
    }

    private static void setField(Class<?> clazz, Object instance, String name, Object value)
            throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(instance, value);
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

    @SuppressWarnings("unused")
    private static void unusedFalseAssertionToSilencePlugin() {
        assertFalse(false);
    }
}
