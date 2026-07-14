package io.pzstorm.storm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StormWorkshopItemProbeTest {

    @Test
    void moderationRemovedBannerClassifiesAsRemoved() {
        String page =
                "<div class=\"error_ctn\">This item has been removed from the community because"
                        + " it violates Steam Community &amp; Content Guidelines. It is only"
                        + " visible to you.</div>";
        String verdict = StormWorkshopItemProbe.classifyPage(page);
        assertTrue(verdict != null && verdict.startsWith("REMOVED BY STEAM MODERATION"), verdict);
    }

    @Test
    void missingItemPageClassifiesAsDeleted() {
        String page = "<h3>That item does not exist. It may have been removed by the author.</h3>";
        String verdict = StormWorkshopItemProbe.classifyPage(page);
        assertTrue(verdict != null && verdict.startsWith("DELETED"), verdict);
    }

    @Test
    void healthyItemPageHasNoVerdict() {
        String page = "<div class=\"workshopItemTitle\">Some Mod</div><div>description</div>";
        assertNull(StormWorkshopItemProbe.classifyPage(page));
    }

    @Test
    void titleIsExtractedFromItemPage() {
        String page = "<div class=\"workshopItemTitle\">[DEV] After The Fall: Economy</div>";
        assertEquals("[DEV] After The Fall: Economy", StormWorkshopItemProbe.extractTitle(page));
        assertNull(StormWorkshopItemProbe.extractTitle("<html>no title div</html>"));
    }
}
