package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The native {@code PhysicsDebugRenderer::instance} prologue and upcall routing. */
class PhysicsDebugRendererTest implements UnitTest {

    private static final List<String> ALL_PROBES =
            List.of(
                    "drawLine(FFFFFFFFFFFF)V",
                    "drawSphere(FFFFFFF)V",
                    "drawTriangle(FFFFFFFFFFFFF)V",
                    "drawContactPoint(FFFFFFFIFFF)V",
                    "drawCapsule(FFIFFFFFFFFFFF)V");

    @BeforeEach
    @AfterEach
    void reset() {
        PhysicsDebugRenderer.resetForTests();
    }

    @Test
    void firstInitResolvesFiveMethodsInOrderOnce() {
        CaptureSink sink = new CaptureSink();
        PhysicsDebugRenderer.sink = sink;
        Object self = new Object();
        assertTrue(PhysicsDebugRenderer.initInstance(self));
        PhysicsDebugRenderer inst = PhysicsDebugRenderer.instance;
        assertNotNull(inst);
        assertEquals(ALL_PROBES, sink.probes);
        assertSame(Object.class, inst.clazz);
        assertTrue(inst.initAttempted && inst.initOk);
        assertTrue(
                inst.mid_drawLine
                        && inst.mid_drawSphere
                        && inst.mid_drawTriangle
                        && inst.mid_drawContactPoint
                        && inst.mid_drawCapsule);

        // later entries: env/self are refreshed, the class and IDs are not re-resolved
        String other = "other";
        assertTrue(PhysicsDebugRenderer.initInstance(other));
        assertSame(inst, PhysicsDebugRenderer.instance);
        assertSame(other, inst.obj);
        assertSame(Object.class, inst.clazz);
        assertEquals(5, sink.probes.size());
    }

    @Test
    void failedLookupThrowsOnceThenReturnsSilently() {
        CaptureSink sink = new CaptureSink("drawTriangle");
        PhysicsDebugRenderer.sink = sink;
        NoSuchMethodError e =
                assertThrows(
                        NoSuchMethodError.class,
                        () -> PhysicsDebugRenderer.initInstance(new Object()));
        assertEquals("drawTriangle", e.getMessage());
        // stops at the first failure
        assertEquals(ALL_PROBES.subList(0, 3), sink.probes);
        PhysicsDebugRenderer inst = PhysicsDebugRenderer.instance;
        assertTrue(inst.initAttempted);
        assertFalse(inst.initOk);
        assertTrue(inst.mid_drawLine && inst.mid_drawSphere);
        assertFalse(inst.mid_drawTriangle || inst.mid_drawContactPoint || inst.mid_drawCapsule);

        Object again = new Object();
        assertFalse(PhysicsDebugRenderer.initInstance(again));
        assertSame(again, inst.obj);
        assertEquals(3, sink.probes.size());
    }

    @Test
    void drawBeforeAnyEntryIsTheNativeNullEnvCrash() {
        PhysicsDebugRenderer.sink = new CaptureSink();
        assertThrows(
                IllegalStateException.class,
                () -> PhysicsDebugRenderer.drawLine(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        // the native allocates the zeroed struct before crashing
        assertNotNull(PhysicsDebugRenderer.instance);
        assertFalse(PhysicsDebugRenderer.instance.initAttempted);
    }

    @Test
    void drawThroughUnresolvedMethodIdIsTheNativeCrash() {
        CaptureSink sink = new CaptureSink("drawContactPoint");
        PhysicsDebugRenderer.sink = sink;
        assertThrows(
                NoSuchMethodError.class, () -> PhysicsDebugRenderer.initInstance(new Object()));
        // drawLine/drawSphere/drawTriangle IDs resolved; drawContactPoint did not
        PhysicsDebugRenderer.drawSphere(1, 2, 3, 4, 5, 6, 7);
        assertEquals(1, sink.calls.size());
        assertThrows(
                IllegalStateException.class,
                () -> PhysicsDebugRenderer.drawContactPoint(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void upcallsReachTheSinkWithTheLatestSelf() {
        CaptureSink sink = new CaptureSink();
        PhysicsDebugRenderer.sink = sink;
        Object a = new Object();
        Object b = new Object();
        PhysicsDebugRenderer.initInstance(a);
        PhysicsDebugRenderer.drawLine(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        PhysicsDebugRenderer.initInstance(b);
        PhysicsDebugRenderer.drawSphere(1, 2, 3, 4, 5, 6, 7);
        PhysicsDebugRenderer.drawTriangle(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        PhysicsDebugRenderer.drawContactPoint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertEquals(List.of(a, b, b, b), sink.receivers);
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawLine", 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f),
                        CaptureSink.line("drawSphere", 1f, 2f, 3f, 4f, 5f, 6f, 7f),
                        CaptureSink.line(
                                "drawTriangle",
                                1f,
                                2f,
                                3f,
                                4f,
                                5f,
                                6f,
                                7f,
                                8f,
                                9f,
                                10f,
                                11f,
                                12f,
                                13f),
                        CaptureSink.line(
                                "drawContactPoint", 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8, 9f, 10f, 11f)),
                sink.calls);
    }

    @Test
    void gameSinkResolvesTheRealRendererMethods() throws Exception {
        // load without initialising (no game statics needed for reflection)
        Class<?> game =
                Class.forName(
                        "zombie.core.physics.PhysicsDebugRenderer",
                        false,
                        getClass().getClassLoader());
        PhysicsDebugRenderer.GameSink s = PhysicsDebugRenderer.GameSink.INSTANCE;
        assertTrue(s.getMethodID(game, "drawLine", "(FFFFFFFFFFFF)V"));
        assertTrue(s.getMethodID(game, "drawSphere", "(FFFFFFF)V"));
        assertTrue(s.getMethodID(game, "drawTriangle", "(FFFFFFFFFFFFF)V"));
        assertTrue(s.getMethodID(game, "drawContactPoint", "(FFFFFFFIFFF)V"));
        assertTrue(s.getMethodID(game, "drawCapsule", "(FFIFFFFFFFFFFF)V"));
        assertFalse(s.getMethodID(game, "drawLine", "(FFFFFF)V"));
        assertFalse(s.getMethodID(Object.class, "drawLine", "(FFFFFFFFFFFF)V"));
    }
}
