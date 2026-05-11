package io.pzstorm.storm.los;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link PlayerLOSReportCache} singleton. The cache is shared global state, so
 * each test clears the underlying map via reflection before running.
 */
class PlayerLOSReportCacheTest implements UnitTest {

    private Map<Short, ?> backingMap;

    @BeforeEach
    void setUp() throws Exception {
        backingMap = cacheReports();
        backingMap.clear();
    }

    @AfterEach
    void tearDown() {
        backingMap.clear();
    }

    @Test
    void getReturnsNullForUnknownId() {
        assertNull(PlayerLOSReportCache.INSTANCE.get((short) 99));
    }

    @Test
    void putThenGetRoundTripsTheReport() {
        PlayerLOSReportCache.Report report = newReport((short) 5, 100L);

        PlayerLOSReportCache.INSTANCE.put(report);

        assertSame(report, PlayerLOSReportCache.INSTANCE.get((short) 5));
    }

    @Test
    void putOverwritesPreviousReportForSamePlayer() {
        PlayerLOSReportCache.Report first = newReport((short) 5, 100L);
        PlayerLOSReportCache.Report second = newReport((short) 5, 200L);

        PlayerLOSReportCache.INSTANCE.put(first);
        PlayerLOSReportCache.INSTANCE.put(second);

        assertSame(second, PlayerLOSReportCache.INSTANCE.get((short) 5));
        assertEquals(1, PlayerLOSReportCache.INSTANCE.size());
    }

    @Test
    void removeDeletesEntry() {
        PlayerLOSReportCache.INSTANCE.put(newReport((short) 5, 100L));

        PlayerLOSReportCache.INSTANCE.remove((short) 5);

        assertNull(PlayerLOSReportCache.INSTANCE.get((short) 5));
        assertEquals(0, PlayerLOSReportCache.INSTANCE.size());
    }

    @Test
    void sizeReflectsNumberOfDistinctPlayers() {
        PlayerLOSReportCache.INSTANCE.put(newReport((short) 1, 100L));
        PlayerLOSReportCache.INSTANCE.put(newReport((short) 2, 200L));
        PlayerLOSReportCache.INSTANCE.put(newReport((short) 3, 300L));

        assertEquals(3, PlayerLOSReportCache.INSTANCE.size());
    }

    @Test
    void getLatestReturnsFreshReport() {
        PlayerLOSReportCache.Report report = newReportAt((short) 5, 1_000_000L);
        PlayerLOSReportCache.INSTANCE.put(report);

        // now = 1_000_050 (50ms after arrival), max age 300ms → fresh.
        assertSame(report, PlayerLOSReportCache.INSTANCE.getLatest((short) 5, 1_000_050L, 300L));
    }

    @Test
    void getLatestReturnsNullForStaleReport() {
        PlayerLOSReportCache.Report report = newReportAt((short) 5, 1_000_000L);
        PlayerLOSReportCache.INSTANCE.put(report);

        // now = 1_000_400 (400ms after arrival), max age 300ms → stale.
        assertNull(PlayerLOSReportCache.INSTANCE.getLatest((short) 5, 1_000_400L, 300L));
    }

    @Test
    void getLatestReturnsNullForUnknownId() {
        assertNull(PlayerLOSReportCache.INSTANCE.getLatest((short) 99, 1_000_000L, 300L));
    }

    @Test
    void getLatestAtExactMaxAgeIsFresh() {
        PlayerLOSReportCache.Report report = newReportAt((short) 5, 1_000_000L);
        PlayerLOSReportCache.INSTANCE.put(report);

        // Boundary: now - arrivedMs == maxAgeMs → still fresh (strict `>` rejects).
        assertSame(report, PlayerLOSReportCache.INSTANCE.getLatest((short) 5, 1_000_300L, 300L));
    }

    private static PlayerLOSReportCache.Report newReport(short id, long clientTick) {
        return newReportAt(id, System.currentTimeMillis(), clientTick);
    }

    private static PlayerLOSReportCache.Report newReportAt(short id, long arrivedMs) {
        return newReportAt(id, arrivedMs, 0L);
    }

    private static PlayerLOSReportCache.Report newReportAt(short id, long arrivedMs, long tick) {
        return new PlayerLOSReportCache.Report(
                id,
                tick,
                0L,
                arrivedMs,
                false,
                false,
                new short[0],
                new boolean[0],
                new boolean[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<Short, ?> cacheReports() throws Exception {
        Field f = PlayerLOSReportCache.class.getDeclaredField("reports");
        f.setAccessible(true);
        return (Map<Short, ?>) f.get(PlayerLOSReportCache.INSTANCE);
    }
}
