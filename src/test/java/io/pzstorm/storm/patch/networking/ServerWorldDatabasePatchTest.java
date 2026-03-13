package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ServerWorldDatabasePatch} reflection helper methods. */
class ServerWorldDatabasePatchTest implements UnitTest {

    @Test
    void getAuthorized_shouldReturnTrueWhenFieldIsTrue() {
        FakeLogonResult result = new FakeLogonResult();
        result.authorized = true;

        Assertions.assertTrue(ServerWorldDatabasePatch.getAuthorized(result));
    }

    @Test
    void getAuthorized_shouldReturnFalseWhenFieldIsFalse() {
        FakeLogonResult result = new FakeLogonResult();
        result.authorized = false;

        Assertions.assertFalse(ServerWorldDatabasePatch.getAuthorized(result));
    }

    @Test
    void getAuthorized_shouldReturnFalseWhenFieldMissing() {
        Assertions.assertFalse(ServerWorldDatabasePatch.getAuthorized(new Object()));
    }

    @Test
    void getStringField_shouldReturnFieldValue() {
        FakeLogonResult result = new FakeLogonResult();
        result.dcReason = "UI_PasswordInvalid";
        result.bannedReason = "Cheating";

        Assertions.assertEquals(
                "UI_PasswordInvalid", ServerWorldDatabasePatch.getStringField(result, "dcReason"));
        Assertions.assertEquals(
                "Cheating", ServerWorldDatabasePatch.getStringField(result, "bannedReason"));
    }

    @Test
    void getStringField_shouldReturnNullWhenFieldIsNull() {
        FakeLogonResult result = new FakeLogonResult();
        result.dcReason = null;

        Assertions.assertNull(ServerWorldDatabasePatch.getStringField(result, "dcReason"));
    }

    @Test
    void getStringField_shouldReturnNullWhenFieldMissing() {
        Assertions.assertNull(
                ServerWorldDatabasePatch.getStringField(new Object(), "nonExistentField"));
    }

    /** Fake object mimicking the package-private fields of ServerWorldDatabase.LogonResult. */
    @SuppressWarnings("unused")
    static class FakeLogonResult {
        boolean authorized;
        String dcReason;
        String bannedReason;
    }
}
