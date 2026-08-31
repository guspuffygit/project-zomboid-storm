package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.connection.LoginQueueEarlyRelease.LoaderTracker;
import org.junit.jupiter.api.Test;
import zombie.network.packets.RequestDataPacket;

/**
 * Behavioral coverage for {@link LoginQueueEarlyRelease}'s pure pieces (terminal-request match,
 * loader tracker sweep, cap clamping) and a live round-trip of {@link LoginQueueReflection} against
 * the real {@code zombie.network.LoginQueue} — the members it resolves are exactly what a game
 * update could rename, so this doubles as the update tripwire.
 */
class LoginQueueEarlyReleaseTest implements UnitTest {

    // -------------------------------------------------- terminal request match

    @Test
    void matchesTheRealWorldMapRequestEnums() throws Exception {
        Object requestType = requestTypeConstant("Request");
        assertTrue(
                LoginQueueEarlyRelease.isTerminalWorldMapRequest(
                        requestType, RequestDataPacket.RequestID.WorldMap));
    }

    @Test
    void rejectsNonTerminalCombinations() throws Exception {
        Object request = requestTypeConstant("Request");
        Object partDataAck = requestTypeConstant("PartDataACK");

        // right type, wrong id — every earlier entry of the download chain
        assertFalse(
                LoginQueueEarlyRelease.isTerminalWorldMapRequest(
                        request, RequestDataPacket.RequestID.ZombieOutfitDescriptors));
        assertFalse(
                LoginQueueEarlyRelease.isTerminalWorldMapRequest(
                        request, RequestDataPacket.RequestID.RadioData));
        // right id, wrong type — mid-transfer ACKs for the WorldMap payload itself
        assertFalse(
                LoginQueueEarlyRelease.isTerminalWorldMapRequest(
                        partDataAck, RequestDataPacket.RequestID.WorldMap));
        // reflection returning null (renamed field on a game update) must fail closed
        assertFalse(
                LoginQueueEarlyRelease.isTerminalWorldMapRequest(
                        null, RequestDataPacket.RequestID.WorldMap));
        assertFalse(LoginQueueEarlyRelease.isTerminalWorldMapRequest(request, null));
        // non-enum garbage must fail closed too
        assertFalse(LoginQueueEarlyRelease.isTerminalWorldMapRequest("Request", "WorldMap"));
    }

    private static Object requestTypeConstant(String name) throws Exception {
        // RequestType is package-private in zombie.network.packets — resolve like production would
        Class<?> type = Class.forName("zombie.network.packets.RequestDataPacket$RequestType");
        for (Object constant : type.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        throw new AssertionError("RequestType." + name + " not found — game update renamed it?");
    }

    // -------------------------------------------------- loader tracker

    @Test
    void trackerCountsOnlyStillLoadingEntries() {
        LoaderTracker tracker = new LoaderTracker();
        tracker.add(1L, 1_000L);
        tracker.add(2L, 1_000L);
        tracker.add(3L, 1_000L);

        // guid 2 finished (fully connected or disconnected) — swept, others stay
        assertEquals(2, tracker.countInFlight(2_000L, 600_000L, guid -> guid != 2L));
        assertEquals(2, tracker.size());
        // sweep is permanent: a later count with everyone loading still excludes guid 2
        assertEquals(2, tracker.countInFlight(3_000L, 600_000L, guid -> true));
    }

    @Test
    void trackerSweepsEntriesPastTheAgeBackstop() {
        LoaderTracker tracker = new LoaderTracker();
        tracker.add(1L, 0L);
        tracker.add(2L, 500_000L);

        // guid 1 is over the backstop even though its connection still reports loading
        assertEquals(1, tracker.countInFlight(600_001L, 600_000L, guid -> true));
        assertEquals(1, tracker.size());
    }

    @Test
    void trackerRemoveReturnsReleaseTimestampOnceAndOnlyForReleasedJoiners() {
        LoaderTracker tracker = new LoaderTracker();
        tracker.add(7L, 42_000L);

        assertEquals(42_000L, tracker.remove(7L));
        assertNull(tracker.remove(7L), "second LoginQueueDone must be a no-op");
        assertNull(tracker.remove(8L), "never-released joiner must be a no-op");
        assertEquals(0, tracker.size());
    }

    // -------------------------------------------------- cap clamping

    @Test
    void clampLoadersEnforcesBounds() {
        assertEquals(
                LoginQueueEarlyRelease.MIN_MAX_CONCURRENT_LOADERS,
                LoginQueueEarlyRelease.clampLoaders(Integer.MIN_VALUE));
        assertEquals(1, LoginQueueEarlyRelease.clampLoaders(0));
        assertEquals(1, LoginQueueEarlyRelease.clampLoaders(1));
        assertEquals(4, LoginQueueEarlyRelease.clampLoaders(4));
        assertEquals(
                LoginQueueEarlyRelease.MAX_MAX_CONCURRENT_LOADERS,
                LoginQueueEarlyRelease.clampLoaders(Integer.MAX_VALUE));
        assertEquals(1, LoginQueueEarlyRelease.DEFAULT_MAX_CONCURRENT_LOADERS);
    }

    @Test
    void setterClampsStoresAndReportsTheAppliedValue() {
        try {
            assertEquals(4, LoginQueueEarlyRelease.setMaxConcurrentLoaders(4));
            assertEquals(4, LoginQueueEarlyRelease.getMaxConcurrentLoaders());

            // sandbox values outside the declared range clamp instead of misconfiguring
            assertEquals(
                    LoginQueueEarlyRelease.MIN_MAX_CONCURRENT_LOADERS,
                    LoginQueueEarlyRelease.setMaxConcurrentLoaders(0));
            assertEquals(
                    LoginQueueEarlyRelease.MAX_MAX_CONCURRENT_LOADERS,
                    LoginQueueEarlyRelease.setMaxConcurrentLoaders(1000));
            assertEquals(
                    LoginQueueEarlyRelease.MAX_MAX_CONCURRENT_LOADERS,
                    LoginQueueEarlyRelease.getMaxConcurrentLoaders());
        } finally {
            LoginQueueEarlyRelease.setMaxConcurrentLoaders(
                    LoginQueueEarlyRelease.DEFAULT_MAX_CONCURRENT_LOADERS);
        }
    }

    // -------------------------------------------------- LoginQueue reflection round-trip

    @Test
    void reflectionResolvesTheRealLoginQueueMembers() throws Exception {
        LoginQueueReflection.resetForTests();
        assertTrue(
                LoginQueueReflection.init(),
                "LoginQueue.LoginQueue / currentLoginQueue / loadNextPlayer() no longer resolve —"
                        + " game update renamed them; early release is silently vanilla until this"
                        + " is fixed");
        // idempotent second call
        assertTrue(LoginQueueReflection.init());

        Object monitor = LoginQueueReflection.queueMonitor();
        assertSame(monitor, LoginQueueReflection.queueMonitor(), "monitor must be stable");

        // the release sequence itself, against the real class with empty queues:
        // clear the (already null) slot, then loadNextPlayer() finds nothing to admit
        assertNull(LoginQueueReflection.currentLoginQueue());
        LoginQueueReflection.clearCurrentLoginQueue();
        assertNull(LoginQueueReflection.currentLoginQueue());
        LoginQueueReflection.loadNextPlayer();
        assertNull(LoginQueueReflection.currentLoginQueue());
    }
}
