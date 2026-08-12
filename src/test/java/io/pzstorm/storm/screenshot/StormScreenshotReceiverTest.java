package io.pzstorm.storm.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.screenshot.StormScreenshotReceiver.PendingScreenshot;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bounds on the attacker-controlled fields of a {@code stormScreenshot/chunk} command.
 *
 * <p>Every assertion here fails against the unbounded version of {@link StormScreenshotReceiver}:
 * {@link StormScreenshotReceiver#isValidFraming(int, int)} did not exist and {@code total} went
 * straight into {@code new String[total]}, and {@code PENDING} was only ever emptied by an upload
 * running to completion.
 */
class StormScreenshotReceiverTest implements UnitTest {

    @BeforeEach
    void clearPending() {
        StormScreenshotReceiver.PENDING.clear();
    }

    @Test
    void framingRejectsChunkCountsThatWouldAllocateAnUnboundedArray() {
        // The exploit: `total` reaches the handler through Double.intValue(), which saturates at
        // Integer.MAX_VALUE, so one packet asks for a ~17 GB String[].
        assertFalse(
                StormScreenshotReceiver.isValidFraming(1, Integer.MAX_VALUE),
                "A saturated total must be rejected before the backing array is allocated");
        assertFalse(
                StormScreenshotReceiver.isValidFraming(1, StormScreenshotReceiver.MAX_CHUNKS + 1),
                "total above MAX_CHUNKS must be rejected");
        assertTrue(
                StormScreenshotReceiver.isValidFraming(1, StormScreenshotReceiver.MAX_CHUNKS),
                "total exactly at MAX_CHUNKS is still a legal upload");
    }

    @Test
    void framingKeepsTheVanillaIndexChecks() {
        assertFalse(StormScreenshotReceiver.isValidFraming(1, 0), "total must be positive");
        assertFalse(StormScreenshotReceiver.isValidFraming(1, -1), "total must be positive");
        assertFalse(StormScreenshotReceiver.isValidFraming(0, 4), "index is 1-based");
        assertFalse(StormScreenshotReceiver.isValidFraming(-1, 4), "index is 1-based");
        assertFalse(StormScreenshotReceiver.isValidFraming(5, 4), "index must be <= total");
        assertTrue(
                StormScreenshotReceiver.isValidFraming(4, 4), "index == total is the last chunk");
        assertTrue(StormScreenshotReceiver.isValidFraming(1, 1), "single-chunk upload is legal");
    }

    @Test
    void sweepDropsUploadsIdleLongerThanTheTtlAndKeepsTheRest() {
        long now = System.nanoTime();
        long overTtl = StormScreenshotReceiver.PENDING_TTL_NANOS + TimeUnit.SECONDS.toNanos(1);

        StormScreenshotReceiver.PENDING.put("alice_fresh", pending("alice", "fresh", now));
        StormScreenshotReceiver.PENDING.put(
                "alice_stale", pending("alice", "stale", now - overTtl));
        StormScreenshotReceiver.PENDING.put("bob_stale", pending("bob", "stale", now - overTtl));

        assertEquals(2, StormScreenshotReceiver.sweepExpired(now), "both stale entries drop");
        assertEquals(1, StormScreenshotReceiver.PENDING.size());
        assertTrue(
                StormScreenshotReceiver.PENDING.containsKey("alice_fresh"),
                "an upload still receiving chunks must survive the sweep");
    }

    @Test
    void sweepKeepsAnUploadSittingExactlyOnTheTtlBoundary() {
        long now = System.nanoTime();
        StormScreenshotReceiver.PENDING.put(
                "alice_edge",
                pending("alice", "edge", now - StormScreenshotReceiver.PENDING_TTL_NANOS + 1));

        assertEquals(0, StormScreenshotReceiver.sweepExpired(now));
        assertEquals(1, StormScreenshotReceiver.PENDING.size());
    }

    @Test
    void perPlayerCountIsScopedToThatPlayer() {
        long now = System.nanoTime();
        StormScreenshotReceiver.PENDING.put("alice_a", pending("alice", "a", now));
        StormScreenshotReceiver.PENDING.put("alice_b", pending("alice", "b", now));
        StormScreenshotReceiver.PENDING.put("bob_a", pending("bob", "a", now));

        assertEquals(2, StormScreenshotReceiver.countPendingFor("alice"));
        assertEquals(1, StormScreenshotReceiver.countPendingFor("bob"));
        assertEquals(0, StormScreenshotReceiver.countPendingFor("carol"));
    }

    private static PendingScreenshot pending(String playerName, String id, long lastChunkNanos) {
        return new PendingScreenshot(playerName, (short) 1, id, 4, lastChunkNanos);
    }
}
