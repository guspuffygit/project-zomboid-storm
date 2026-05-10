package io.pzstorm.storm.los;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.StubKahluaTable;
import io.pzstorm.storm.los.commands.LOSReportCommand;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Unit tests for {@link PlayerLOSReportHandler#onLOSReport}. {@code onEveryOneMinute} is exercised
 * end-to-end on a live server; here we focus on payload parsing and the cache write contract.
 */
class PlayerLOSReportHandlerTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    private boolean savedServerFlag;
    private Map<Short, ?> cacheReports;

    @BeforeEach
    void setUp() throws Exception {
        savedServerFlag = GameServer.server;
        GameServer.server = true;
        cacheReports = cacheReports();
        cacheReports.clear();
    }

    @AfterEach
    void tearDown() {
        cacheReports.clear();
        GameServer.server = savedServerFlag;
    }

    @Test
    void onLOSReportIsNoOpWhenServerFlagIsFalse() throws Exception {
        GameServer.server = false;
        IsoPlayer player = newPlayer((short) 5);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, payload((short) 5)));

        assertNull(PlayerLOSReportCache.INSTANCE.get((short) 5));
    }

    @Test
    void onLOSReportIsNoOpWhenSenderIsNull() {
        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(null, payload((short) 5)));

        assertEquals(0, PlayerLOSReportCache.INSTANCE.size());
    }

    @Test
    void onLOSReportWritesPayloadFieldsIntoCache() throws Exception {
        IsoPlayer player = newPlayer((short) 5);
        StubKahluaTable args = payload((short) 5);
        args.rawset("tick", 100.0);
        args.rawset("wallMs", 1700000000000.0);
        args.rawset("selfSpotted", true);
        args.rawset("truncated", true);

        long before = System.currentTimeMillis();
        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, args));
        long after = System.currentTimeMillis();

        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get((short) 5);
        assertNotNull(report);
        assertEquals((short) 5, report.playerOnlineID);
        assertEquals(100L, report.clientTick);
        assertEquals(1700000000000L, report.clientWallMs);
        assertTrue(report.selfSpotted);
        assertTrue(report.truncated);
        assertTrue(
                report.arrivedMs >= before && report.arrivedMs <= after,
                "arrivedMs should be stamped at receive time");
    }

    @Test
    void onLOSReportFallsBackToSenderOnlineIDWhenPayloadIDIsMissing() throws Exception {
        IsoPlayer player = newPlayer((short) 9);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, new StubKahluaTable()));

        // Payload had no playerOnlineID → handler used sender's onlineID.
        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get((short) 9);
        assertNotNull(report);
        assertEquals((short) 9, report.playerOnlineID);
    }

    @Test
    void onLOSReportUsesPayloadIDWhenPresent() throws Exception {
        // Payload claims a different ID than the sender — current contract trusts the payload.
        IsoPlayer player = newPlayer((short) 9);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, payload((short) 5)));

        assertNotNull(PlayerLOSReportCache.INSTANCE.get((short) 5));
        assertNull(PlayerLOSReportCache.INSTANCE.get((short) 9));
    }

    @Test
    void onLOSReportStoresEmptyArraysWhenEntriesTableIsAbsent() throws Exception {
        IsoPlayer player = newPlayer((short) 5);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, payload((short) 5)));

        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get((short) 5);
        assertNotNull(report);
        assertEquals(0, report.ids.length);
        assertEquals(0, report.couldSee.length);
        assertEquals(0, report.canSee.length);
    }

    @Test
    void onLOSReportParsesEntriesArrayIntoParallelShortAndBooleanArrays() throws Exception {
        IsoPlayer player = newPlayer((short) 5);
        StubKahluaTable args = payload((short) 5);

        StubKahluaTable entries = new StubKahluaTable();
        entries.rawset(1, entry(101, true, false));
        entries.rawset(2, entry(202, false, true));
        entries.rawset(3, entry(303, true, true));
        args.rawset("entries", entries);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, args));

        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get((short) 5);
        assertNotNull(report);
        assertArrayEquals(new short[] {101, 202, 303}, report.ids);
        assertArrayEquals(new boolean[] {true, false, true}, report.couldSee);
        assertArrayEquals(new boolean[] {false, true, true}, report.canSee);
    }

    @Test
    void onLOSReportTreatsMissingEntryFieldsAsFalseAndMinusOneId() throws Exception {
        IsoPlayer player = newPlayer((short) 5);
        StubKahluaTable args = payload((short) 5);

        // Entry with no id, no couldSee, no canSee — should produce id=-1, both flags=false.
        StubKahluaTable entries = new StubKahluaTable();
        entries.rawset(1, new StubKahluaTable());
        args.rawset("entries", entries);

        PlayerLOSReportHandler.onLOSReport(new LOSReportCommand(player, args));

        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get((short) 5);
        assertNotNull(report);
        assertEquals(1, report.ids.length);
        assertEquals((short) -1, report.ids[0]);
        assertFalse(report.couldSee[0]);
        assertFalse(report.canSee[0]);
    }

    private static StubKahluaTable payload(short playerOnlineID) {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("playerOnlineID", (double) playerOnlineID);
        return args;
    }

    private static StubKahluaTable entry(int id, boolean couldSee, boolean canSee) {
        StubKahluaTable e = new StubKahluaTable();
        e.rawset("id", (double) id);
        e.rawset("couldSee", couldSee);
        e.rawset("canSee", canSee);
        return e;
    }

    private static IsoPlayer newPlayer(short onlineId) throws Exception {
        IsoPlayer p = (IsoPlayer) UNSAFE.allocateInstance(IsoPlayer.class);
        Field f = IsoPlayer.class.getDeclaredField("onlineId");
        f.setAccessible(true);
        f.set(p, onlineId);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<Short, ?> cacheReports() throws Exception {
        Field f = PlayerLOSReportCache.class.getDeclaredField("reports");
        f.setAccessible(true);
        return (Map<Short, ?>) f.get(PlayerLOSReportCache.INSTANCE);
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to acquire sun.misc.Unsafe", e);
        }
    }
}
