package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.ImportantAreasMetrics;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.ImportantArea;

/**
 * The pure half of {@link ImportantAreasPolicy}: the clamp, the victim choice, the cap arithmetic
 * with an explicit clock, and the once-a-second warning. No class loading, no engine singleton.
 */
class ImportantAreasPolicyTest implements UnitTest {

    private static final int T = ImportantAreasPolicy.AREA_TILES;

    @BeforeEach
    void reset() {
        ImportantAreasPolicy.resetForTest();
        ImportantAreasMetrics.resetForTest();
    }

    @AfterEach
    void restore() {
        ImportantAreasPolicy.resetForTest();
        ImportantAreasMetrics.resetForTest();
    }

    @Test
    void defaultIsVanillaAndTheClampHoldsBothEnds() {
        assertEquals(100, ImportantAreasPolicy.VANILLA_MAXIMUM);
        assertEquals(ImportantAreasPolicy.VANILLA_MAXIMUM, ImportantAreasPolicy.getMaximum());
        assertEquals(100, ImportantAreasPolicy.clampMaximum(99));
        assertEquals(100, ImportantAreasPolicy.clampMaximum(Integer.MIN_VALUE));
        assertEquals(300, ImportantAreasPolicy.clampMaximum(300));
        assertEquals(1024, ImportantAreasPolicy.clampMaximum(1024));
        assertEquals(1024, ImportantAreasPolicy.clampMaximum(5000));
        assertEquals(250, ImportantAreasPolicy.setMaximum(250));
        assertEquals(250, ImportantAreasPolicy.getMaximum());
        assertEquals(100, ImportantAreasPolicy.setMaximum(0));
    }

    @Test
    void victimIsTheOldestStampTiesGoToTheEarliestBooked() {
        List<ImportantArea> areas =
                List.of(area(0, 0, 500L), area(1, 0, 200L), area(2, 0, 200L), area(3, 0, 900L));
        assertEquals(1, ImportantAreasPolicy.indexOfLeastRecentlyRefreshed(areas));
        assertEquals(-1, ImportantAreasPolicy.indexOfLeastRecentlyRefreshed(List.of()));
    }

    @Test
    void addsUnderTheCapStampedWithTheClockGiven() {
        LinkedList<ImportantArea> areas = new LinkedList<>();
        ImportantArea a = ImportantAreasPolicy.updateOrAdd(areas, 3 * T + 1, 4 * T + 2, 100, 777L);
        assertNotNull(a);
        assertEquals(3, a.sx);
        assertEquals(4, a.sy);
        assertEquals(777L, a.lastUpdate);
        assertEquals(1, areas.size());
        assertEquals(1, ImportantAreasMetrics.size);

        ImportantArea again = ImportantAreasPolicy.updateOrAdd(areas, 3 * T + 60, 4 * T, 100, 900L);
        assertSame(a, again, "any tile in the same 64x64 area is the same entry");
        assertEquals(900L, a.lastUpdate);
        assertEquals(1, areas.size());
    }

    @Test
    void negativeCoordinatesDivideTowardsMinusInfinityLikeVanilla() {
        LinkedList<ImportantArea> areas = new LinkedList<>();
        ImportantArea a = ImportantAreasPolicy.updateOrAdd(areas, -1, -T - 1, 100, 1L);
        assertEquals(-1, a.sx);
        assertEquals(-2, a.sy);
    }

    @Test
    void atTheCapTheOldestGoesAndTheCallerGetsNull() {
        LinkedList<ImportantArea> areas = new LinkedList<>();
        for (int i = 0; i < 100; i++) {
            ImportantAreasPolicy.updateOrAdd(areas, i * T, 0, 100, 1_000L + i);
        }
        areas.get(30).lastUpdate = 5L;

        assertNull(ImportantAreasPolicy.updateOrAdd(areas, 200 * T, 0, 100, 9_999L));
        assertEquals(99, areas.size());
        assertTrue(areas.stream().noneMatch(a -> a.sx == 30), "the stale entry was the victim");
        assertTrue(areas.stream().noneMatch(a -> a.sx == 200), "the newcomer was not added");
        assertEquals(1, ImportantAreasMetrics.evictions);
        assertEquals(99, ImportantAreasMetrics.size);

        // The freed slot is taken on the next booking, as vanilla's 99/100 oscillation does.
        assertNotNull(ImportantAreasPolicy.updateOrAdd(areas, 200 * T, 0, 100, 10_000L));
        assertEquals(100, areas.size());
    }

    @Test
    void aHigherCapAdmitsMoreBeforeEvicting() {
        LinkedList<ImportantArea> areas = new LinkedList<>();
        for (int i = 0; i < 300; i++) {
            assertNotNull(ImportantAreasPolicy.updateOrAdd(areas, i * T, 0, 300, 1L + i));
        }
        assertEquals(300, areas.size());
        assertNull(ImportantAreasPolicy.updateOrAdd(areas, 300 * T, 0, 300, 5_000L));
        assertEquals(299, areas.size());
        assertTrue(areas.stream().noneMatch(a -> a.sx == 0), "the oldest of the 300 went");
    }

    /** One warning line per second at most, carrying the evictions folded into it. */
    @Test
    void theWarningIsRateLimitedAndTheEvictionsAreStillCounted() {
        LinkedList<ImportantArea> areas = new LinkedList<>();
        for (int i = 0; i < 100; i++) {
            ImportantAreasPolicy.updateOrAdd(areas, i * T, 0, 100, 1_000L + i);
        }
        // Cap pinned at the current size so every call below is a miss.
        for (int i = 0; i < 50; i++) {
            assertNull(ImportantAreasPolicy.updateOrAdd(areas, (1_000 + i) * T, 0, areas.size(), 20_000L));
        }
        assertEquals(50, ImportantAreasMetrics.evictions);
        assertEquals(1, ImportantAreasMetrics.warnings, "fifty misses in one instant, one line");

        assertNull(ImportantAreasPolicy.updateOrAdd(areas, 2_000 * T, 0, areas.size(), 20_999L));
        assertEquals(1, ImportantAreasMetrics.warnings, "still inside the second");
        assertNull(ImportantAreasPolicy.updateOrAdd(areas, 2_001 * T, 0, areas.size(), 21_000L));
        assertEquals(2, ImportantAreasMetrics.warnings, "the second is up: one more line");
        assertEquals(52, ImportantAreasMetrics.evictions);
    }

    private static ImportantArea area(int sx, int sy, long stamp) {
        ImportantArea a = new ImportantArea(sx, sy);
        a.lastUpdate = stamp;
        return a;
    }
}
