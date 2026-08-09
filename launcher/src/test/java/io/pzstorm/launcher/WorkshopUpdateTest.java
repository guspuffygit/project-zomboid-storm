package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkshopUpdateTest {

    @Test
    void allItemsRefusedByARunningSteamIsDefinitive() {
        assertTrue(new WorkshopUpdate.Result(false, 67, 67, true).nothingUpdated());
        assertTrue(new WorkshopUpdate.Result(false, 1, 1, true).nothingUpdated());
    }

    @Test
    void partialFailuresFallBackToTheInGameFlow() {
        assertFalse(new WorkshopUpdate.Result(false, 3, 67, true).nothingUpdated());
    }

    @Test
    void steamUnavailableOrNoChildIsNotDefinitive() {
        assertFalse(
                new WorkshopUpdate.Result(false, 67, 67, false).nothingUpdated(),
                "the in-game flow may still succeed when the child never ran");
        assertFalse(new WorkshopUpdate.Result(true, 0, 0, false).nothingUpdated());
    }

    @Test
    void successIsNeverDefinitiveFailure() {
        assertFalse(new WorkshopUpdate.Result(true, 0, 67, true).nothingUpdated());
    }
}
