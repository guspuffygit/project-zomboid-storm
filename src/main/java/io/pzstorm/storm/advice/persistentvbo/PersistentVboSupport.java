package io.pzstorm.storm.advice.persistentvbo;

import io.pzstorm.storm.logging.StormLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * GL side of the persistent-VBO patch. Every method is called from advice inlined into {@code
 * GLVertexBufferObject} or {@code SpriteRenderer.postRender()}, on the render thread, and none of
 * them lets a throwable escape: a failure logs once and either disables the feature for new
 * instances or degrades this call to the safest thing that still keeps the instance usable.
 *
 * <p>A persistent instance owns {@link #SLOTS} immutable GL buffers, each mapped once for the life
 * of the process. {@link #acquire} rotates to the next slot, waits on the {@link FrameFenceRing}
 * until the GPU has finished the frame that last read it, binds it and hands back its mapped
 * buffer. {@link #release} stamps the slot with the current frame. The instance's {@code id} field
 * always names the active slot, so vanilla {@code bind()} / {@code getID()} follow the rotation
 * without knowing about it.
 *
 * <p>Adoption waits for the first {@link #endFrame()}: if {@code SpriteRendererFrameFencePatch} did
 * not weave, the frame counter never moves and no instance is ever adopted.
 */
public final class PersistentVboSupport {

    public static final int SLOTS = 4;
    static final int FENCE_RING = 8;
    static final long MAX_SIZE = 4L << 20;
    static final long WAIT_TIMEOUT_NANOS = 1_000_000_000L;

    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    private static final int STORAGE_FLAGS =
            GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    private static final FrameFenceRing RING =
            new FrameFenceRing(new LwjglSyncGl(), FENCE_RING, WAIT_TIMEOUT_NANOS);

    private static boolean disabled;
    private static boolean ringBroken;
    private static int capable;

    /** Per-instance slot state; advice carries it as {@code Object}. */
    public static final class State {
        final int type;
        final long size;
        final int[] names = new int[SLOTS];
        final ByteBuffer[] buffers = new ByteBuffer[SLOTS];
        final long[] writtenFrame = new long[SLOTS];
        int slot = -1;

        State(int type, long size) {
            this.type = type;
            this.size = size;
            for (int i = 0; i < SLOTS; i++) {
                writtenFrame[i] = -1;
            }
        }
    }

    private PersistentVboSupport() {}

    /**
     * Turns the buffer named {@code id} into a persistent instance. Returns the new state, or
     * {@code null} when the instance is not eligible or the feature is off, in which case nothing
     * GL-visible has changed and vanilla {@code map()} proceeds as usual.
     */
    public static Object adopt(int id, long size, int type) {
        if (disabled || id == 0 || RING.currentFrame() < 1) {
            return null;
        }
        if (size <= 0 || size > MAX_SIZE) {
            return null;
        }
        if (type != GL_ARRAY_BUFFER && type != GL_ELEMENT_ARRAY_BUFFER) {
            return null;
        }
        State state = new State(type, size);
        try {
            drainGlErrors();
            for (int i = 0; i < SLOTS; i++) {
                state.names[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(type, state.names[i]);
                GL44.glBufferStorage(type, size, STORAGE_FLAGS);
                ByteBuffer mapped = GL30.glMapBufferRange(type, 0L, size, STORAGE_FLAGS);
                if (mapped == null) {
                    throw new IllegalStateException("glMapBufferRange returned null");
                }
                state.buffers[i] = mapped.order(ByteOrder.nativeOrder());
            }
            int error = GL11.glGetError();
            if (error != 0) {
                throw new IllegalStateException("GL error 0x" + Integer.toHexString(error));
            }
        } catch (Throwable t) {
            disable("Persistent VBO allocation failed; new buffers stay on vanilla mapping", t);
            try {
                releaseNames(state);
                GL15.glBindBuffer(type, id);
            } catch (Throwable ignored) {
                // Already disabled; vanilla map() gets whatever binding survived.
            }
            return null;
        }
        GL15.glDeleteBuffers(id);
        return state;
    }

    /** Rotates to the next slot, waits for the GPU to be done with it, binds it and returns it. */
    public static ByteBuffer acquire(Object stateObject) {
        State state = (State) stateObject;
        state.slot = (state.slot + 1) % SLOTS;
        waitForSlot(state.writtenFrame[state.slot]);
        try {
            GL15.glBindBuffer(state.type, state.names[state.slot]);
        } catch (Throwable t) {
            disable("Persistent VBO bind failed", t);
        }
        ByteBuffer buffer = state.buffers[state.slot];
        buffer.clear().limit((int) state.size);
        return buffer;
    }

    public static boolean isPersistent(Object stateObject) {
        return stateObject != null;
    }

    /** GL name of the slot handed out by the last {@link #acquire}. */
    public static int activeName(Object stateObject) {
        State state = (State) stateObject;
        return state.names[state.slot];
    }

    /** Marks the active slot as written in the current frame. */
    public static void release(Object stateObject) {
        State state = (State) stateObject;
        if (state.slot >= 0) {
            state.writtenFrame[state.slot] = RING.currentFrame();
        }
    }

    /** Deletes every slot buffer; deleting a mapped buffer unmaps it. */
    public static void destroy(Object stateObject) {
        State state = (State) stateObject;
        try {
            releaseNames(state);
        } catch (Throwable t) {
            disable("Persistent VBO delete failed", t);
        }
    }

    /** Per-frame draw boundary: fences the frame just drawn. */
    public static void endFrame() {
        // disabled only stops adoption: instances already adopted still need real frame fences.
        if (ringBroken || capable < 0) {
            return;
        }
        if (capable == 0) {
            capable = checkCapabilities() ? 1 : -1;
            if (capable < 0) {
                disabled = true;
                return;
            }
        }
        try {
            RING.endFrame();
        } catch (Throwable t) {
            ringBroken = true;
            disable("Persistent VBO frame fence failed; existing buffers fall back to glFinish", t);
        }
    }

    private static void waitForSlot(long writtenFrame) {
        if (writtenFrame < 0) {
            return;
        }
        if (!ringBroken) {
            try {
                RING.waitForFrame(writtenFrame);
                return;
            } catch (Throwable t) {
                ringBroken = true;
                disable(
                        "Persistent VBO fence wait failed; existing buffers fall back to glFinish",
                        t);
            }
        }
        try {
            GL11.glFinish();
        } catch (Throwable ignored) {
            // The context is gone; nothing left to synchronise against.
        }
    }

    private static boolean checkCapabilities() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            boolean storage = hasBufferStorage(caps);
            boolean sync = hasSync(caps);
            if (!storage || !sync) {
                StormLogger.LOGGER.info(
                        "Persistent VBO mapping unavailable (buffer_storage={}, sync={});"
                                + " using vanilla buffer mapping",
                        storage,
                        sync);
                return false;
            }
            StormLogger.LOGGER.info(
                    "Persistent VBO mapping enabled ({} slots per buffer, {} frame fences)",
                    SLOTS,
                    FENCE_RING);
            return true;
        } catch (Throwable t) {
            StormLogger.LOGGER.warn(
                    "Persistent VBO capability check failed; using vanilla buffer mapping", t);
            return false;
        }
    }

    /**
     * Whether the current thread's GL context can persistently map buffers and fence frames. Throws
     * when the thread has no context.
     */
    static boolean contextSupportsPersistentMapping() {
        GLCapabilities caps = GL.getCapabilities();
        return hasBufferStorage(caps) && hasSync(caps);
    }

    private static boolean hasBufferStorage(GLCapabilities caps) {
        return caps.OpenGL44 || caps.GL_ARB_buffer_storage;
    }

    private static boolean hasSync(GLCapabilities caps) {
        return caps.OpenGL32 || caps.GL_ARB_sync;
    }

    private static void releaseNames(State state) {
        for (int i = 0; i < SLOTS; i++) {
            if (state.names[i] != 0) {
                GL15.glDeleteBuffers(state.names[i]);
                state.names[i] = 0;
            }
            state.buffers[i] = null;
        }
    }

    private static void drainGlErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != 0; i++) {}
    }

    private static void disable(String message, Throwable cause) {
        if (!disabled) {
            StormLogger.LOGGER.warn(message, cause);
        }
        disabled = true;
    }
}
