package io.pzstorm.storm.advice.persistentvbo;

import io.pzstorm.storm.logging.StormLogger;
import org.lwjgl.opengl.GL32;

/**
 * One GPU fence per rendered frame, kept in a ring of {@code R} slots, plus the bookkeeping that
 * tells a persistently mapped buffer slot whether the GPU has finished reading it.
 *
 * <p>Frames are numbered from 0. {@link #endFrame()} runs at the per-frame draw boundary and fences
 * the frame just recorded. {@link #waitForFrame(long)} is called before a buffer slot last written
 * in frame {@code F} is handed out again and blocks only when the GPU may still be reading it.
 * {@code completedThroughFrame} is a watermark: a signalled fence for frame {@code F} proves every
 * frame at or below {@code F} complete, because GL processes commands in order.
 *
 * <p>Invariant: a slot's fence is waited on before it is recycled, so any frame older than {@code
 * frame - R} is complete and needs no fence. A timed-out or failed wait never advances the
 * watermark: it throws so {@link PersistentVboSupport} disables the ring and synchronizes existing
 * persistent buffers with its {@code glFinish} fallback before they are reused.
 *
 * <p>Single-threaded by construction; every caller is on the render thread.
 */
public final class FrameFenceRing {

    private final SyncGl gl;
    private final long[] fences;
    private final long[] fenceFrames;
    private final long timeoutNanos;
    private long frame;
    private long completedThroughFrame = -1;
    private int timeouts;

    public FrameFenceRing(SyncGl gl, int ringSize, long timeoutNanos) {
        if (ringSize < 1) {
            throw new IllegalArgumentException("ringSize must be >= 1");
        }
        this.gl = gl;
        this.fences = new long[ringSize];
        this.fenceFrames = new long[ringSize];
        this.timeoutNanos = timeoutNanos;
    }

    /** Index of the frame currently being recorded. */
    public long currentFrame() {
        return frame;
    }

    /** Highest frame index the GPU is known to have finished. */
    public long completedThroughFrame() {
        return completedThroughFrame;
    }

    public int timeouts() {
        return timeouts;
    }

    /** Fences the frame just recorded and advances the frame counter. */
    public void endFrame() {
        int slot = (int) (frame % fences.length);
        if (fences[slot] != 0) {
            waitAndMark(fenceFrames[slot], fences[slot]);
            gl.deleteSync(fences[slot]);
            fences[slot] = 0;
        }
        fences[slot] = createFence();
        fenceFrames[slot] = frame;
        frame++;
    }

    /** Blocks until the GPU has finished every command issued in frame {@code writtenFrame}. */
    public void waitForFrame(long writtenFrame) {
        if (writtenFrame <= completedThroughFrame) {
            return;
        }
        if (writtenFrame >= frame) {
            long fence = createFence();
            try {
                waitOn(fence);
            } finally {
                gl.deleteSync(fence);
            }
            return;
        }
        int slot = (int) (writtenFrame % fences.length);
        if (fences[slot] != 0 && fenceFrames[slot] == writtenFrame) {
            waitAndMark(writtenFrame, fences[slot]);
            return;
        }
        completedThroughFrame = Math.max(completedThroughFrame, writtenFrame);
    }

    private void waitAndMark(long fencedFrame, long fence) {
        if (fencedFrame > completedThroughFrame) {
            waitOn(fence);
            completedThroughFrame = fencedFrame;
        }
    }

    private long createFence() {
        long fence = gl.fenceSync();
        if (fence == 0) {
            throw new IllegalStateException("Persistent VBO fence allocation returned zero");
        }
        return fence;
    }

    private void waitOn(long fence) {
        int status = gl.clientWaitSync(fence, timeoutNanos);
        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            return;
        }
        timeouts++;
        if (timeouts == 1) {
            StormLogger.LOGGER.warn(
                    "Persistent VBO fence wait did not complete within {} ms (status 0x{});"
                            + " switching existing buffers to glFinish synchronization",
                    timeoutNanos / 1_000_000L,
                    Integer.toHexString(status));
        }
        throw new IllegalStateException(
                "Persistent VBO fence wait did not complete (status 0x"
                        + Integer.toHexString(status)
                        + ")");
    }
}
