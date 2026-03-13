package io.pzstorm.storm.event;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnAuthAttemptEvent;
import io.pzstorm.storm.event.lua.OnPlayerDisconnectedEvent;
import io.pzstorm.storm.event.lua.OnPlayerFullyConnectedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Integration tests verifying networking events can be dispatched and received by handlers. */
class NetworkingEventDispatchTest implements IntegrationTest {

    @Test
    void shouldDispatchOnPlayerFullyConnectedEvent() {
        ConnectedHandler handler = new ConnectedHandler();
        StormEventDispatcher.registerEventHandler(handler);

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
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("testUser", handler.receivedUsername);
        Assertions.assertEquals("192.168.1.5", handler.receivedIp);
        Assertions.assertEquals(76561198012345678L, handler.receivedSteamId);
    }

    @Test
    void shouldDispatchOnPlayerDisconnectedEvent() {
        DisconnectedHandler handler = new DisconnectedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnPlayerDisconnectedEvent event =
                new OnPlayerDisconnectedEvent(
                        "leavingUser",
                        "LeavingDisplay",
                        "10.0.0.1",
                        11111111111L,
                        "steam:11111111111",
                        "player",
                        55544433L,
                        (short) 4,
                        500.0f,
                        600.0f,
                        1.0f);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("leavingUser", handler.receivedUsername);
        Assertions.assertEquals("10.0.0.1", handler.receivedIp);
    }

    @Test
    void shouldDispatchOnAuthAttemptEvent() {
        AuthHandler handler = new AuthHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent("authUser", "172.16.0.1", 99999999999L, 1, true, null, null);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("authUser", handler.receivedUsername);
        Assertions.assertTrue(handler.receivedAuthorized);
    }

    @Test
    void shouldDispatchAuthFailureEvent() {
        AuthHandler handler = new AuthHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent(
                        "hacker", "10.0.0.99", 0L, 1, false, "UI_PasswordInvalid", "Cheating");
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertFalse(handler.receivedAuthorized);
        Assertions.assertEquals("UI_PasswordInvalid", handler.receivedDcReason);
        Assertions.assertEquals("Cheating", handler.receivedBannedReason);
    }

    @Test
    void shouldNotDispatchConnectedEventToDisconnectedHandler() {
        DisconnectedHandler handler = new DisconnectedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnPlayerFullyConnectedEvent event =
                new OnPlayerFullyConnectedEvent(
                        "user", "User", "1.2.3.4", 0L, 0L, "", "player", 0L, 0, (short) 0, 0f, 0f,
                        0f);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertFalse(handler.wasCalled);
    }

    // ---- Test handlers ----

    public static class ConnectedHandler {
        boolean wasCalled = false;
        String receivedUsername;
        String receivedIp;
        long receivedSteamId;

        @SubscribeEvent
        public void onConnected(OnPlayerFullyConnectedEvent event) {
            wasCalled = true;
            receivedUsername = event.username;
            receivedIp = event.ip;
            receivedSteamId = event.steamId;
        }
    }

    public static class DisconnectedHandler {
        boolean wasCalled = false;
        String receivedUsername;
        String receivedIp;

        @SubscribeEvent
        public void onDisconnected(OnPlayerDisconnectedEvent event) {
            wasCalled = true;
            receivedUsername = event.username;
            receivedIp = event.ip;
        }
    }

    public static class AuthHandler {
        boolean wasCalled = false;
        String receivedUsername;
        boolean receivedAuthorized;
        String receivedDcReason;
        String receivedBannedReason;

        @SubscribeEvent
        public void onAuth(OnAuthAttemptEvent event) {
            wasCalled = true;
            receivedUsername = event.username;
            receivedAuthorized = event.authorized;
            receivedDcReason = event.dcReason;
            receivedBannedReason = event.bannedReason;
        }
    }
}
