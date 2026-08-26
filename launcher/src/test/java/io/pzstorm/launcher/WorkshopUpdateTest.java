package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkshopUpdateTest {

    @Test
    void anyFailureFromARunningSteamBlocksTheJoin() {
        assertTrue(joinBlocked(new WorkshopUpdate.Result(false, 67, 67, true)));
        assertTrue(joinBlocked(new WorkshopUpdate.Result(false, 1, 1, true)));
        assertTrue(
                joinBlocked(new WorkshopUpdate.Result(false, 2, 67, true)),
                "a client missing even one item is mismatched with the server");
    }

    @Test
    void steamUnavailableOrNoChildFallsBackToTheInGameFlow() {
        assertFalse(
                joinBlocked(new WorkshopUpdate.Result(false, 67, 67, false)),
                "the in-game flow may still succeed when the child never ran");
        assertFalse(joinBlocked(new WorkshopUpdate.Result(true, 0, 0, false)));
    }

    @Test
    void successNeverBlocksTheJoin() {
        assertFalse(joinBlocked(new WorkshopUpdate.Result(true, 0, 67, true)));
        assertNull(JoinFlow.joinBlocker(new WorkshopUpdate.Result(true, 0, 67, true)));
    }

    @Test
    void blockerNamesTheFailedItems() {
        Set<String> failed = new LinkedHashSet<>();
        failed.add("3752227135");
        failed.add("3671847630");
        SteamRestartRequiredException blocker =
                JoinFlow.joinBlocker(new WorkshopUpdate.Result(false, 2, 67, true, failed));
        assertTrue(blocker.summary().contains("2 of 67"));
        assertTrue(blocker.summary().contains("3752227135, 3671847630"));
        assertTrue(blocker.getMessage().contains("Restart Steam"));
        assertTrue(blocker.getMessage().contains("Send Logs"));
    }

    @Test
    void blockerCountsFailuresWhenNoIdsWereParsed() {
        SteamRestartRequiredException blocker =
                JoinFlow.joinBlocker(new WorkshopUpdate.Result(false, 3, 67, true));
        assertTrue(blocker.summary().contains("3 of 67"));
    }

    @Test
    void repairFailureMessageNamesItemsAndTheWorkingFix() {
        String message = JoinFlow.repairFailedMessage(java.util.List.of("2928660831"));
        assertTrue(message.contains("2928660831"));
        assertTrue(message.contains("unsubscribe"), "the fix that works must be spelled out");
        assertTrue(
                message.contains("Restarting Steam does NOT clear this"),
                "must counter the restart-Steam advice the other failure paths give");
    }

    @Test
    void nonZeroExitWithNoParsedFailuresStillBlocks() {
        SteamRestartRequiredException blocker =
                JoinFlow.joinBlocker(new WorkshopUpdate.Result(false, 0, 67, true));
        assertTrue(blocker.summary().contains("1 of 67"));
    }

    @Test
    void failedItemIdsAreExposed() {
        Set<String> failed = new LinkedHashSet<>();
        failed.add("3670772371");
        WorkshopUpdate.Result r = new WorkshopUpdate.Result(false, 1, 67, true, failed);
        assertTrue(r.failedItemIds.contains("3670772371"));
        assertEquals(1, r.failedItemIds.size());
    }

    @Test
    void failedItemIdsDefaultsEmptyOnLegacyConstructor() {
        WorkshopUpdate.Result r = new WorkshopUpdate.Result(true, 0, 0, false);
        assertTrue(r.failedItemIds.isEmpty());
    }

    private static boolean joinBlocked(WorkshopUpdate.Result result) {
        return JoinFlow.joinBlocker(result) != null;
    }
}
