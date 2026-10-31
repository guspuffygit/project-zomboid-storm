package io.pzstorm.storm.advice.persistentvbo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL32;

/**
 * Pins what {@link PersistentVboSupport#endFrame()} does once the feature is disabled. A failed
 * adoption only stops new adoptions: instances adopted earlier keep rotating, so the frame counter
 * has to keep moving and the fences have to keep coming, or each of their maps creates and waits on
 * a fresh fence. Runs against a fake {@link SyncGl} swapped into the real ring, since the feature
 * needs no GL context up to that point.
 */
class PersistentVboSupportEndFrameTest implements UnitTest {

    private static final int GL_ARRAY_BUFFER = 0x8892;

    private static final class CountingGl implements SyncGl {
        private long nextHandle = 1;
        int fences;

        @Override
        public long fenceSync() {
            fences++;
            return nextHandle++;
        }

        @Override
        public int clientWaitSync(long fence, long timeoutNanos) {
            return GL32.GL_ALREADY_SIGNALED;
        }

        @Override
        public void deleteSync(long fence) {}
    }

    private final CountingGl gl = new CountingGl();
    private FrameFenceRing ring;
    private Object realGl;

    @BeforeEach
    void useFakeGl() throws Exception {
        ring = (FrameFenceRing) field(PersistentVboSupport.class, "RING").get(null);
        realGl = field(FrameFenceRing.class, "gl").get(ring);
        field(FrameFenceRing.class, "gl").set(ring, gl);
        setLatches(1, false, false);
    }

    @AfterEach
    void restore() throws Exception {
        field(FrameFenceRing.class, "gl").set(ring, realGl);
        setLatches(0, false, false);
    }

    @Test
    void framesKeepMovingAfterAFailedAdoption() throws Exception {
        long start = ring.currentFrame();
        setLatches(1, true, false);

        PersistentVboSupport.endFrame();
        PersistentVboSupport.endFrame();

        assertEquals(start + 2, ring.currentFrame());
        assertEquals(2, gl.fences);
    }

    @Test
    void aSlotAdoptedBeforeTheFailureDoesNotStallItsNextMap() throws Exception {
        PersistentVboSupport.endFrame();
        PersistentVboSupport.State state = new PersistentVboSupport.State(GL_ARRAY_BUFFER, 16L);
        state.slot = 0;
        PersistentVboSupport.release(state);
        long written = state.writtenFrame[0];

        setLatches(1, true, false);
        PersistentVboSupport.endFrame();
        PersistentVboSupport.endFrame();

        int fencesBeforeMaps = gl.fences;
        ring.waitForFrame(written);
        ring.waitForFrame(written);
        assertEquals(fencesBeforeMaps, gl.fences, "a map must not create its own fence");
    }

    @Test
    void aBrokenRingStaysUntouched() throws Exception {
        long start = ring.currentFrame();
        setLatches(1, true, true);

        PersistentVboSupport.endFrame();

        assertEquals(start, ring.currentFrame());
        assertEquals(0, gl.fences);
    }

    @Test
    void anIncapableDriverNeverFences() throws Exception {
        long start = ring.currentFrame();
        setLatches(-1, true, false);

        PersistentVboSupport.endFrame();

        assertEquals(start, ring.currentFrame());
        assertEquals(0, gl.fences);
    }

    private static void setLatches(int capable, boolean disabled, boolean ringBroken)
            throws Exception {
        field(PersistentVboSupport.class, "capable").setInt(null, capable);
        field(PersistentVboSupport.class, "disabled").setBoolean(null, disabled);
        field(PersistentVboSupport.class, "ringBroken").setBoolean(null, ringBroken);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
