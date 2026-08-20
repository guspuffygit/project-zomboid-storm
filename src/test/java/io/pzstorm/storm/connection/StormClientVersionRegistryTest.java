package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StormClientVersionRegistryTest implements UnitTest {

    @BeforeEach
    void reset() {
        StormClientVersionRegistry.reset();
    }

    @Test
    void recordsVersionPerConnectionAndLaterHelloReplacesIt() {
        assertEquals("2.6.10", StormClientVersionRegistry.record(7L, "2.6.10"));
        assertEquals("2.6.10", StormClientVersionRegistry.versionOf(7L));
        StormClientVersionRegistry.record(7L, "2.6.11");
        assertEquals("2.6.11", StormClientVersionRegistry.versionOf(7L));
        assertNull(StormClientVersionRegistry.versionOf(8L), "unknown connections have no entry");
    }

    @Test
    void rejectsMissingOrBlankVersionsAndTruncatesLongOnes() {
        assertNull(StormClientVersionRegistry.record(1L, null));
        assertNull(StormClientVersionRegistry.record(1L, "   "));
        assertNull(
                StormClientVersionRegistry.versionOf(1L), "a bad hello must not create an entry");

        String tooLong = "x".repeat(StormClientVersionRegistry.MAX_VERSION_LENGTH + 10);
        String stored = StormClientVersionRegistry.record(2L, tooLong);
        assertEquals(StormClientVersionRegistry.MAX_VERSION_LENGTH, stored.length());
        assertEquals(
                " 42.20.3_2.6.10 ".strip(),
                StormClientVersionRegistry.record(3L, " 42.20.3_2.6.10 "));
    }

    @Test
    void sweepDropsOnlyDeadConnections() {
        StormClientVersionRegistry.record(1L, "a");
        StormClientVersionRegistry.record(2L, "b");
        StormClientVersionRegistry.record(3L, "c");

        assertEquals(2, StormClientVersionRegistry.sweep(guid -> guid == 2L));

        assertNull(StormClientVersionRegistry.versionOf(1L));
        assertEquals("b", StormClientVersionRegistry.versionOf(2L));
        assertNull(StormClientVersionRegistry.versionOf(3L));
        assertEquals(1, StormClientVersionRegistry.size());
    }

    @Test
    void needsSweepOnlyPastThreshold() {
        for (long guid = 0; guid < StormClientVersionRegistry.SWEEP_THRESHOLD; guid++) {
            StormClientVersionRegistry.record(guid, "v");
        }
        assertFalse(StormClientVersionRegistry.needsSweep(), "exactly at threshold is fine");
        StormClientVersionRegistry.record(Long.MAX_VALUE, "v");
        assertTrue(StormClientVersionRegistry.needsSweep());
    }
}
