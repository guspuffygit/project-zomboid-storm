package io.pzstorm.storm.advice.persistentvbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL32;

/**
 * Exercises {@link FrameFenceRing} against a fake {@link SyncGl}: one fence per frame, waits only
 * when a slot's frame is past the watermark, the watermark advancing on signalled waits, an
 * in-frame reuse forcing an immediate fence, ring recycling waiting before delete, and a timeout
 * never being accepted as proof that the GPU has finished reading a buffer.
 */
class FrameFenceRingTest implements UnitTest {

    private static final int RING = 4;
    private static final long TIMEOUT = 1_000_000_000L;

    private static final class FakeGl implements SyncGl {
        long nextHandle = 100;
        int waitResult = GL32.GL_ALREADY_SIGNALED;
        boolean failAllocation;
        boolean throwOnWait;
        final List<Long> waited = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        final Set<Long> live = new HashSet<>();

        @Override
        public long fenceSync() {
            if (failAllocation) return 0;
            long handle = nextHandle++;
            live.add(handle);
            return handle;
        }

        @Override
        public int clientWaitSync(long fence, long timeoutNanos) {
            assertTrue(live.contains(fence), "waited on a fence that was never created/deleted");
            assertEquals(TIMEOUT, timeoutNanos);
            waited.add(fence);
            if (throwOnWait) throw new IllegalStateException("lost GL context");
            return waitResult;
        }

        @Override
        public void deleteSync(long fence) {
            assertTrue(live.remove(fence), "deleted an unknown or already-deleted fence");
            deleted.add(fence);
        }
    }

    @Test
    void neverWrittenSlotNeedsNoWait() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        ring.waitForFrame(-1);
        assertTrue(gl.waited.isEmpty());
        assertEquals(0, ring.currentFrame());
    }

    @Test
    void endFrameIssuesOneFencePerFrame() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        for (int i = 0; i < 3; i++) {
            ring.endFrame();
        }
        assertEquals(3, ring.currentFrame());
        assertEquals(3, gl.live.size());
        assertTrue(gl.waited.isEmpty());
        assertTrue(gl.deleted.isEmpty());
    }

    @Test
    void waitOnRecentFrameAdvancesWatermarkAndIsNotRepeated() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        ring.endFrame();
        ring.endFrame();
        ring.endFrame();
        long fenceOfFrame1 = 101;

        ring.waitForFrame(1);
        assertEquals(List.of(fenceOfFrame1), gl.waited);
        assertEquals(1, ring.completedThroughFrame());

        ring.waitForFrame(1);
        ring.waitForFrame(0);
        assertEquals(1, gl.waited.size(), "frames at or below the watermark must not wait");

        ring.waitForFrame(2);
        assertEquals(List.of(fenceOfFrame1, 102L), gl.waited);
        assertEquals(2, ring.completedThroughFrame());
        assertTrue(gl.deleted.isEmpty(), "waiting must not delete ring fences");
    }

    @Test
    void reuseWithinCurrentFrameForcesImmediateFence() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        ring.endFrame();
        ring.endFrame();

        ring.waitForFrame(2);
        assertEquals(List.of(102L), gl.waited);
        assertEquals(List.of(102L), gl.deleted);
        assertEquals(-1, ring.completedThroughFrame(), "an unfinished frame is never marked");
        assertEquals(2, gl.live.size());
    }

    @Test
    void recyclingASlotWaitsThenDeletesTheOldFence() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        for (int i = 0; i < RING; i++) {
            ring.endFrame();
        }
        assertTrue(gl.waited.isEmpty());

        ring.endFrame();
        assertEquals(List.of(100L), gl.waited);
        assertEquals(List.of(100L), gl.deleted);
        assertEquals(0, ring.completedThroughFrame());
        assertEquals(RING, gl.live.size());

        ring.waitForFrame(0);
        assertEquals(1, gl.waited.size(), "a recycled frame is complete by invariant");
    }

    @Test
    void frameOlderThanRingIsCompleteWithoutWaiting() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        for (int i = 0; i < RING * 3; i++) {
            ring.endFrame();
        }
        int waitsFromRecycling = gl.waited.size();
        assertEquals(RING * 2, waitsFromRecycling);
        assertEquals(RING * 2 - 1, ring.completedThroughFrame());

        ring.waitForFrame(3);
        assertEquals(waitsFromRecycling, gl.waited.size());
    }

    @Test
    void timeoutDoesNotAdvanceWatermarkOrDeleteRingFence() {
        FakeGl gl = new FakeGl();
        gl.waitResult = GL32.GL_TIMEOUT_EXPIRED;
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        ring.endFrame();
        ring.endFrame();

        assertThrows(IllegalStateException.class, () -> ring.waitForFrame(0));
        assertEquals(1, ring.timeouts());
        assertEquals(-1, ring.completedThroughFrame());
        assertTrue(gl.deleted.isEmpty());

        gl.waitResult = GL32.GL_CONDITION_SATISFIED;
        ring.waitForFrame(0);
        assertEquals(2, gl.waited.size(), "completion still requires a successful wait");
        assertEquals(0, ring.completedThroughFrame());
        assertFalse(gl.live.isEmpty());
    }

    @Test
    void failedOrUnknownWaitStatusDoesNotMarkFrameComplete() {
        for (int status : new int[] {GL32.GL_WAIT_FAILED, 0}) {
            FakeGl gl = new FakeGl();
            gl.waitResult = status;
            FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
            ring.endFrame();

            assertThrows(IllegalStateException.class, () -> ring.waitForFrame(0));
            assertEquals(-1, ring.completedThroughFrame());
            assertEquals(1, ring.currentFrame());
            assertEquals(Set.of(100L), gl.live);
        }
    }

    @Test
    void timeoutDuringRecyclingPreservesOldFenceAndFrame() {
        FakeGl gl = new FakeGl();
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
        for (int i = 0; i < RING; i++) ring.endFrame();
        gl.waitResult = GL32.GL_TIMEOUT_EXPIRED;

        assertThrows(IllegalStateException.class, ring::endFrame);
        assertEquals(RING, ring.currentFrame());
        assertEquals(-1, ring.completedThroughFrame());
        assertEquals(RING, gl.live.size());
        assertTrue(gl.deleted.isEmpty());
        assertEquals(104, gl.nextHandle, "no new fence may replace the unsignaled fence");
    }

    @Test
    void failedInFrameWaitAlwaysDeletesItsTemporaryFence() {
        for (boolean throwOnWait : new boolean[] {false, true}) {
            FakeGl gl = new FakeGl();
            gl.waitResult = GL32.GL_TIMEOUT_EXPIRED;
            gl.throwOnWait = throwOnWait;
            FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);
            ring.endFrame();

            assertThrows(IllegalStateException.class, () -> ring.waitForFrame(1));
            assertEquals(-1, ring.completedThroughFrame());
            assertEquals(1, ring.currentFrame());
            assertEquals(List.of(101L), gl.deleted);
            assertEquals(Set.of(100L), gl.live);
        }
    }

    @Test
    void zeroFenceHandleNeverAdvancesFrameOrCompletion() {
        FakeGl gl = new FakeGl();
        gl.failAllocation = true;
        FrameFenceRing ring = new FrameFenceRing(gl, RING, TIMEOUT);

        assertThrows(IllegalStateException.class, ring::endFrame);
        assertThrows(IllegalStateException.class, () -> ring.waitForFrame(0));
        assertEquals(0, ring.currentFrame());
        assertEquals(-1, ring.completedThroughFrame());
        assertTrue(gl.waited.isEmpty());
        assertTrue(gl.deleted.isEmpty());
        assertTrue(gl.live.isEmpty());
    }
}
