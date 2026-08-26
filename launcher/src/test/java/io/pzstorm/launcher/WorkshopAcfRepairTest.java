package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkshopAcfRepairTest {

    // Real appworkshop_108600.acf shape: the same item id appears under BOTH
    // WorkshopItemsInstalled (the record boot reconciliation compares against disk) and
    // WorkshopItemDetails (cached published metadata, with nested sub-blocks).
    private static final String ACF =
            "\"AppWorkshop\"\n"
                    + "{\n"
                    + "\t\"appid\"\t\t\"108600\"\n"
                    + "\t\"SizeOnDisk\"\t\t\"14425859182\"\n"
                    + "\t\"NeedsUpdate\"\t\t\"1\"\n"
                    + "\t\"WorkshopItemsInstalled\"\n"
                    + "\t{\n"
                    + "\t\t\"2757712197\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"size\"\t\t\"1234\"\n"
                    + "\t\t\t\"timeupdated\"\t\t\"1779337481\"\n"
                    + "\t\t\t\"manifest\"\t\t\"7710781033129390792\"\n"
                    + "\t\t}\n"
                    + "\t\t\"2409333430\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"size\"\t\t\"22633937\"\n"
                    + "\t\t\t\"timeupdated\"\t\t\"1783017543\"\n"
                    + "\t\t}\n"
                    + "\t}\n"
                    + "\t\"WorkshopItemDetails\"\n"
                    + "\t{\n"
                    + "\t\t\"2757712197\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"manifest\"\t\t\"7710781033129390792\"\n"
                    + "\t\t\t\"subscriptions\"\n"
                    + "\t\t\t{\n"
                    + "\t\t\t\t\"nested\"\t\t\"{value}\"\n"
                    + "\t\t\t}\n"
                    + "\t\t}\n"
                    + "\t\t\"2409333430\"\n"
                    + "\t\t{\n"
                    + "\t\t\t\"timetouched\"\t\t\"1754000000\"\n"
                    + "\t\t}\n"
                    + "\t}\n"
                    + "}\n";

    @Test
    void stripsBothBlocksOfTheItemAndNothingElse() {
        String result = WorkshopAcfRepair.stripped(ACF, List.of("2757712197"));
        assertFalse(result.contains("2757712197"), "item record fully removed");
        // sibling item and top-level leaves untouched
        assertEquals(
                Map.of("2409333430", 1783017543L),
                WorkshopStaleScan.parseInstalledTimestamps(result));
        assertTrue(result.contains("\"SizeOnDisk\"\t\t\"14425859182\""));
        assertTrue(result.contains("\"timetouched\"\t\t\"1754000000\""));
        // still balanced vdf: strip of the other item leaves an installed-empty file
        String both = WorkshopAcfRepair.stripped(result, List.of("2409333430"));
        assertTrue(WorkshopStaleScan.parseInstalledTimestamps(both).isEmpty());
        assertTrue(both.contains("\"WorkshopItemsInstalled\""), "parent block survives empty");
    }

    @Test
    void nestedBracesAndBracesInsideQuotedValuesDoNotDerailTheBlockMatch() {
        String result = WorkshopAcfRepair.stripped(ACF, List.of("2409333430"));
        // 2757712197's details block contains a nested sub-block and a "{value}" string;
        // both must survive intact when only the sibling is stripped
        assertTrue(result.contains("\"nested\"\t\t\"{value}\""));
        assertEquals(
                Map.of("2757712197", 1779337481L),
                WorkshopStaleScan.parseInstalledTimestamps(result));
        assertFalse(result.contains("2409333430"));
    }

    @Test
    void unknownItemLeavesTheTextIdentical() {
        assertEquals(ACF, WorkshopAcfRepair.stripped(ACF, List.of("999999")));
        assertEquals(ACF, WorkshopAcfRepair.stripped(ACF, List.of()));
        // an id that only exists as a leaf VALUE elsewhere must not trigger a strip
        assertEquals(ACF, WorkshopAcfRepair.stripped(ACF, List.of("108600")));
    }

    @Test
    void stripInstallRecordsRewritesTheFileAndKeepsABackup(@TempDir Path dir) throws IOException {
        Path acf = dir.resolve("appworkshop_108600.acf");
        Files.writeString(acf, ACF, StandardCharsets.UTF_8);
        List<String> removed =
                WorkshopAcfRepair.stripInstallRecords(acf, List.of("2757712197", "999999"));
        assertEquals(List.of("2757712197"), removed, "only ids actually recorded count");
        String rewritten = Files.readString(acf, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains("2757712197"));
        Path backup = dir.resolve("appworkshop_108600.acf" + WorkshopAcfRepair.BACKUP_SUFFIX);
        assertEquals(ACF, Files.readString(backup, StandardCharsets.UTF_8), "original backed up");
    }

    @Test
    void noRecordedIdsNeverRewritesTheFile(@TempDir Path dir) throws IOException {
        Path acf = dir.resolve("appworkshop_108600.acf");
        Files.writeString(acf, ACF, StandardCharsets.UTF_8);
        assertTrue(WorkshopAcfRepair.stripInstallRecords(acf, List.of("999999")).isEmpty());
        assertEquals(ACF, Files.readString(acf, StandardCharsets.UTF_8));
        assertFalse(
                Files.exists(
                        dir.resolve("appworkshop_108600.acf" + WorkshopAcfRepair.BACKUP_SUFFIX)),
                "no backup for a no-op");
        assertTrue(WorkshopAcfRepair.stripInstallRecords(null, List.of("2757712197")).isEmpty());
        assertTrue(
                WorkshopAcfRepair.stripInstallRecords(dir.resolve("missing.acf"), List.of("1"))
                        .isEmpty());
    }
}
