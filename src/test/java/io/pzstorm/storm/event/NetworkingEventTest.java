package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.lua.OnAuthAttemptEvent;
import io.pzstorm.storm.event.lua.OnPlayerDisconnectedEvent;
import io.pzstorm.storm.event.lua.OnPlayerFullyConnectedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for networking event construction and field access. */
class NetworkingEventTest implements UnitTest {

    @Test
    void onPlayerFullyConnectedEvent_shouldStoreAllFields() {
        OnPlayerFullyConnectedEvent event =
                new OnPlayerFullyConnectedEvent(
                        "testUser",
                        "TestDisplay",
                        "192.168.1.5",
                        76561198012345678L,
                        76561198012345678L,
                        "steam:76561198012345678",
                        "admin",
                        99887766L,
                        3,
                        (short) 12,
                        1050.5f,
                        2030.3f,
                        0.0f);

        Assertions.assertEquals("testUser", event.username);
        Assertions.assertEquals("TestDisplay", event.displayName);
        Assertions.assertEquals("192.168.1.5", event.ip);
        Assertions.assertEquals(76561198012345678L, event.steamId);
        Assertions.assertEquals(76561198012345678L, event.ownerId);
        Assertions.assertEquals("steam:76561198012345678", event.idStr);
        Assertions.assertEquals("admin", event.roleName);
        Assertions.assertEquals(99887766L, event.connectedGuid);
        Assertions.assertEquals(3, event.connectionIndex);
        Assertions.assertEquals((short) 12, event.onlineId);
        Assertions.assertEquals(1050.5f, event.x);
        Assertions.assertEquals(2030.3f, event.y);
        Assertions.assertEquals(0.0f, event.z);
    }

    @Test
    void onPlayerDisconnectedEvent_shouldStoreAllFields() {
        OnPlayerDisconnectedEvent event =
                new OnPlayerDisconnectedEvent(
                        "disconnUser",
                        "DisconnDisplay",
                        "10.0.0.1",
                        11111111111L,
                        "steam:11111111111",
                        "player",
                        55544433L,
                        (short) 4,
                        500.0f,
                        600.0f,
                        1.0f);

        Assertions.assertEquals("disconnUser", event.username);
        Assertions.assertEquals("DisconnDisplay", event.displayName);
        Assertions.assertEquals("10.0.0.1", event.ip);
        Assertions.assertEquals(11111111111L, event.steamId);
        Assertions.assertEquals("steam:11111111111", event.idStr);
        Assertions.assertEquals("player", event.roleName);
        Assertions.assertEquals(55544433L, event.connectedGuid);
        Assertions.assertEquals((short) 4, event.onlineId);
        Assertions.assertEquals(500.0f, event.x);
        Assertions.assertEquals(600.0f, event.y);
        Assertions.assertEquals(1.0f, event.z);
    }

    @Test
    void onAuthAttemptEvent_shouldStoreAllFields() {
        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent("authUser", "172.16.0.1", 99999999999L, 1, true, null, null);

        Assertions.assertEquals("authUser", event.username);
        Assertions.assertEquals("172.16.0.1", event.ip);
        Assertions.assertEquals(99999999999L, event.steamId);
        Assertions.assertEquals(1, event.authType);
        Assertions.assertTrue(event.authorized);
        Assertions.assertNull(event.dcReason);
        Assertions.assertNull(event.bannedReason);
    }

    @Test
    void onAuthAttemptEvent_shouldStoreFailureDetails() {
        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent(
                        "banned", "10.0.0.99", 0L, 1, false, "UI_PasswordInvalid", "Cheating");

        Assertions.assertFalse(event.authorized);
        Assertions.assertEquals("UI_PasswordInvalid", event.dcReason);
        Assertions.assertEquals("Cheating", event.bannedReason);
    }

    @Test
    void onPlayerFullyConnectedEvent_shouldHandleNullFields() {
        OnPlayerFullyConnectedEvent event =
                new OnPlayerFullyConnectedEvent(
                        null, null, null, 0L, 0L, null, null, 0L, 0, (short) 0, 0f, 0f, 0f);

        Assertions.assertNull(event.username);
        Assertions.assertNull(event.displayName);
        Assertions.assertNull(event.ip);
        Assertions.assertEquals(0L, event.steamId);
    }

    @Test
    void onPlayerDisconnectedEvent_shouldHandleNullFields() {
        OnPlayerDisconnectedEvent event =
                new OnPlayerDisconnectedEvent(
                        null, null, null, 0L, null, null, 0L, (short) 0, 0f, 0f, 0f);

        Assertions.assertNull(event.username);
        Assertions.assertNull(event.displayName);
        Assertions.assertNull(event.ip);
    }
}
