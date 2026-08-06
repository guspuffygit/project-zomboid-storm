package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
