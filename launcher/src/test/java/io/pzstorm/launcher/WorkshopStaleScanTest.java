package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkshopStaleScanTest {

    // Mirrors Steam's appworkshop_108600.acf: WorkshopItemDetails repeats every id with its own
    // "timeupdated" (Steam's cached published time) — the parser must not read that block.
    private static final String ACF =
            "\"AppWorkshop\"\n"
                    + "{\n"
                    + "\t\"appid\"\t\t\"108600\"\n"
                    + "\t\"SizeOnDisk\"\t\t\"123\"\n"
                    + "\t\"WorkshopItemsInstalled\"\n"
                    + "\t{\n"
                    + "\t\t\"2335368829\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"size\"\t\t\"877568832\"\n"
                    + "\t\t\t\"timeupdated\"\t\t\"1779337481\"\n"
                    + "\t\t\t\"manifest\"\t\t\"6820580383168566549\"\n"
                    + "\t\t}\n"
                    + "\t\t\"3739256725\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"size\"\t\t\"512\"\n"
                    + "\t\t\t\"timeupdated\"\t\t\"1754300000\"\n"
                    + "\t\t}\n"
                    + "\t\t\"badid-x\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"timeupdated\"\t\t\"111\"\n"
                    + "\t\t}\n"
                    + "\t\t\"999\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"timeupdated\"\t\t\"not-a-number\"\n"
                    + "\t\t}\n"
                    + "\t}\n"
                    + "\t\"WorkshopItemDetails\"\n"
                    + "\t{\n"
                    + "\t\t\"2335368829\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"manifest\"\t\t\"6820580383168566549\"\n"
                    + "\t\t\t\"timeupdated\"\t\t\"9999999999\"\n"
                    + "\t\t\t\"timetouched\"\t\t\"1754000000\"\n"
                    + "\t\t}\n"
                    + "\t}\n"
                    + "}\n";

    @Test
    void readsTimestampsOnlyFromInstalledBlock() {
        Map<String, Long> installed = WorkshopStaleScan.parseInstalledTimestamps(ACF);
        assertEquals(Map.of("2335368829", 1779337481L, "3739256725", 1754300000L), installed);
    }

    @Test
    void toleratesMissingInstalledBlock() {
        assertTrue(WorkshopStaleScan.parseInstalledTimestamps("\"AppWorkshop\"\n{\n}\n").isEmpty());
        assertTrue(WorkshopStaleScan.parseInstalledTimestamps("").isEmpty());
    }

    @Test
    void publishedTimestampsSkipHiddenAndDeletedItems() {
        String json =
                "{\"response\":{\"result\":1,\"resultcount\":3,\"publishedfiledetails\":["
                        + "{\"publishedfileid\":\"2335368829\",\"result\":1,"
                        + "\"time_updated\":1779337481,\"title\":\"ok\"},"
                        + "{\"publishedfileid\":\"3676481910\",\"result\":9},"
                        + "{\"publishedfileid\":\"111\",\"result\":1}"
                        + "]}}";
        Map<String, Long> published = WorkshopStaleScan.parsePublishedTimestamps(json);
        assertEquals(Map.of("2335368829", 1779337481L), published);
    }

    @Test
    void publishedTimestampsTolerateUnexpectedShape() {
        assertTrue(WorkshopStaleScan.parsePublishedTimestamps("{\"response\":{}}").isEmpty());
        assertTrue(WorkshopStaleScan.parsePublishedTimestamps("{}").isEmpty());
    }

    @Test
    void scanProvesCurrentOnlyForInstalledMatchingItems() {
        Map<String, Long> installed = Map.of("100", 50L, "200", 50L, "400", 50L);
        Map<String, Long> published = Map.of("100", 50L, "200", 60L, "999", 1L);
        WorkshopStaleScan.Scan scan = new WorkshopStaleScan.Scan(installed, published);
        assertTrue(scan.isCurrent("100"), "installed with matching published timestamp");
        assertFalse(scan.isCurrent("200"), "installed but published diverged");
        assertFalse(
                scan.isCurrent("400"),
                "no anonymous published details (hidden upstream) — the game's logged-in query"
                        + " still compares timestamps, so this must take the full confirm path");
        assertFalse(scan.isCurrent("999"), "never installed locally");
        assertEquals(List.of("200"), scan.staleInstalled());
    }

    @Test
    void staleMeansAnyInequalityAndUnknownPublishedIsNotStale() {
        Map<String, Long> installed =
                Map.of(
                        "100", 50L, // matches -> fresh
                        "200", 50L, // published newer -> stale
                        "300", 99L, // published OLDER still counts (game uses !=)
                        "400", 50L); // hidden/deleted, no published time -> skipped
        Map<String, Long> published = Map.of("100", 50L, "200", 60L, "300", 40L, "999", 1L);
        List<String> stale = WorkshopStaleScan.staleItems(installed, published);
        assertEquals(List.of("200", "300"), stale);
    }

    @Test
    void staleAmongJudgesAFreshAcfReadAgainstTheScansPublishedTimes() {
        // original scan: both items were stale before the update pass
        WorkshopStaleScan.Scan scan =
                new WorkshopStaleScan.Scan(
                        Map.of("100", 10L, "200", 10L), Map.of("100", 50L, "200", 50L));
        // fresh acf read: Steam rewrote 100's install stamp, 200's stayed behind
        Map<String, Long> fresh = Map.of("100", 50L, "200", 10L, "300", 1L);
        assertEquals(List.of("200"), scan.staleAmong(List.of("100", "200"), fresh));
    }

    @Test
    void staleAmongIgnoresStaleItemsOutsideTheAttemptedSet() {
        WorkshopStaleScan.Scan scan =
                new WorkshopStaleScan.Scan(Map.of("200", 10L), Map.of("200", 50L, "300", 50L));
        Map<String, Long> fresh = Map.of("200", 10L, "300", 10L);
        assertEquals(
                List.of("200"),
                scan.staleAmong(List.of("200"), fresh),
                "300 was never part of the update attempt, so it must not trigger a repair");
    }
}
