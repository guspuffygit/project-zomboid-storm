package io.pzstorm.storm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StormWorkshopItemProbeTest {

    @Test
    void publiclyVisibleItemKeepsTitleAndSuggestsTransientFailure() {
        String json =
                "{\"response\":{\"result\":1,\"resultcount\":1,\"publishedfiledetails\":["
                        + "{\"publishedfileid\":\"2335368829\",\"result\":1,"
                        + "\"title\":\"Authentic Z\"}]}}";
        Map<Long, StormWorkshopItemProbe.ProbeResult> results =
                StormWorkshopItemProbe.parseDetails(json);
        StormWorkshopItemProbe.ProbeResult result = results.get(2335368829L);
        assertEquals("Authentic Z", result.title);
        assertTrue(result.verdict.startsWith("publicly visible"), result.verdict);
    }

    @Test
    void fileNotFoundItemHasNoTitleAndListsIndistinguishableCauses() {
        String json =
                "{\"response\":{\"result\":1,\"resultcount\":1,\"publishedfiledetails\":["
                        + "{\"publishedfileid\":\"3717669793\",\"result\":9}]}}";
        Map<Long, StormWorkshopItemProbe.ProbeResult> results =
                StormWorkshopItemProbe.parseDetails(json);
        StormWorkshopItemProbe.ProbeResult result = results.get(3717669793L);
        assertNull(result.title);
        assertTrue(result.verdict.startsWith("not visible to anonymous accounts"), result.verdict);
    }

    @Test
    void unexpectedResultCodeIsReportedVerbatim() {
        String json =
                "{\"response\":{\"result\":1,\"resultcount\":1,\"publishedfiledetails\":["
                        + "{\"publishedfileid\":\"42\",\"result\":16}]}}";
        Map<Long, StormWorkshopItemProbe.ProbeResult> results =
                StormWorkshopItemProbe.parseDetails(json);
        assertTrue(results.get(42L).verdict.contains("result 16"), results.get(42L).verdict);
    }

    @Test
    void malformedResponsesYieldNoEntries() {
        assertTrue(StormWorkshopItemProbe.parseDetails("not json at all").isEmpty());
        assertTrue(StormWorkshopItemProbe.parseDetails("{\"response\":{}}").isEmpty());
        assertTrue(
                StormWorkshopItemProbe.parseDetails(
                                "{\"response\":{\"publishedfiledetails\":[{\"result\":9}]}}")
                        .isEmpty());
    }
}
