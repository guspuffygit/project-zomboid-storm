package io.pzstorm.storm.los.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.StubKahluaTable;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Unit tests for {@link LOSReportCommand} accessors.
 *
 * <p>Numeric payload values arrive from Lua as {@code Double}, so the accessors must coerce them to
 * {@code short}/{@code long} and return safe defaults for missing keys.
 */
class LOSReportCommandTest implements UnitTest {

    @Test
    void getPlayerOnlineIDReturnsMinusOneWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertEquals((short) -1, cmd.getPlayerOnlineID());
    }

    @Test
    void getPlayerOnlineIDReturnsValueFromPayload() {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("playerOnlineID", 7.0);

        LOSReportCommand cmd = new LOSReportCommand(null, args);

        assertEquals((short) 7, cmd.getPlayerOnlineID());
    }

    @Test
    void getTickReturnsZeroWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertEquals(0L, cmd.getTick());
    }

    @Test
    void getTickReturnsValueFromPayload() {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("tick", 12345.0);

        LOSReportCommand cmd = new LOSReportCommand(null, args);

        assertEquals(12345L, cmd.getTick());
    }

    @Test
    void getWallMsReturnsZeroWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertEquals(0L, cmd.getWallMs());
    }

    @Test
    void getWallMsReturnsValueFromPayload() {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("wallMs", 1700000000000.0);

        LOSReportCommand cmd = new LOSReportCommand(null, args);

        assertEquals(1700000000000L, cmd.getWallMs());
    }

    @Test
    void isSelfSpottedReturnsFalseWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertFalse(cmd.isSelfSpotted());
    }

    @Test
    void isSelfSpottedReflectsPayloadFlag() {
        StubKahluaTable trueArgs = new StubKahluaTable();
        trueArgs.rawset("selfSpotted", true);
        assertTrue(new LOSReportCommand(null, trueArgs).isSelfSpotted());

        StubKahluaTable falseArgs = new StubKahluaTable();
        falseArgs.rawset("selfSpotted", false);
        assertFalse(new LOSReportCommand(null, falseArgs).isSelfSpotted());
    }

    @Test
    void isTruncatedReturnsFalseWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertFalse(cmd.isTruncated());
    }

    @Test
    void isTruncatedReflectsPayloadFlag() {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("truncated", true);
        assertTrue(new LOSReportCommand(null, args).isTruncated());
    }

    @Test
    void getEntriesTableReturnsNullWhenMissing() {
        LOSReportCommand cmd = new LOSReportCommand(null, new StubKahluaTable());
        assertNull(cmd.getEntriesTable());
    }

    @Test
    void getEntriesTableReturnsTableWhenPresent() {
        StubKahluaTable args = new StubKahluaTable();
        StubKahluaTable entries = new StubKahluaTable();
        args.rawset("entries", entries);

        LOSReportCommand cmd = new LOSReportCommand(null, args);

        KahluaTable result = cmd.getEntriesTable();
        assertNotNull(result);
        assertEquals(entries, result);
    }

    @Test
    void getEntriesTableReturnsNullWhenValueIsNotATable() {
        StubKahluaTable args = new StubKahluaTable();
        args.rawset("entries", "not-a-table");

        LOSReportCommand cmd = new LOSReportCommand(null, args);

        assertNull(cmd.getEntriesTable());
    }
}
