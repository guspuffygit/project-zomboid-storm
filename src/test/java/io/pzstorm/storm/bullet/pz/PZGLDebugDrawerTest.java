package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PZGLDebugDrawer} upcall arguments: offset added in double, then narrowed once. */
class PZGLDebugDrawerTest implements UnitTest {

    private CaptureSink sink;
    private PZGLDebugDrawer drawer;

    @BeforeEach
    void setUp() {
        PhysicsDebugRenderer.resetForTests();
        sink = new CaptureSink();
        PhysicsDebugRenderer.sink = sink;
        PhysicsDebugRenderer.initInstance(new Object());
        drawer = new PZGLDebugDrawer();
        drawer.m_offset.x = -3.0;
        drawer.m_offset.y = 0.0;
        drawer.m_offset.z = 16777217.0;
        drawer.m_offset.w = 0.0;
    }

    @AfterEach
    void tearDown() {
        PhysicsDebugRenderer.resetForTests();
    }

    private static float n(double v) {
        return (float) v;
    }

    private float ox(double v) {
        return (float) (v + drawer.m_offset.x);
    }

    private float oy(double v) {
        return (float) (v + drawer.m_offset.y);
    }

    private float oz(double v) {
        return (float) (v + drawer.m_offset.z);
    }

    @Test
    void chosenValuesDistinguishDoubleAddFromFloatAdd() {
        // guards the test data: a float-add port would fail the tests below
        assertNotEquals((float) (5.55 + -3.0), (float) 5.55 + (float) -3.0);
        assertNotEquals((float) (0.1 + 16777217.0), (float) 0.1 + (float) 16777217.0);
    }

    @Test
    void constructorState() {
        PZGLDebugDrawer d = new PZGLDebugDrawer();
        assertEquals(0, d.getDebugMode());
        assertEquals(0.0, d.m_offset.x);
        assertEquals(0.0, d.m_offset.z);
    }

    @Test
    void drawLine4() {
        drawer.drawLine(
                new btVector3(5.55, 1.7, 0.1),
                new btVector3(-2.25, 0.3, 0.7),
                new btVector3(0.1, 0.2, 0.3),
                new btVector3(0.4, 0.5, 0.6));
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawLine",
                                2.55f,
                                1.7f,
                                n(0.1 + 16777217.0),
                                ox(-2.25),
                                0.3f,
                                oz(0.7),
                                0.1f,
                                0.2f,
                                0.3f,
                                0.4f,
                                0.5f,
                                0.6f)),
                sink.calls);
        assertEquals(2.55f, ox(5.55));
    }

    @Test
    void drawLine3RepeatsTheColour() {
        drawer.drawLine(
                new btVector3(5.55, 1.7, 0.1),
                new btVector3(1, 2, 3),
                new btVector3(0.7, 0.8, 0.9));
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawLine",
                                ox(5.55),
                                oy(1.7),
                                oz(0.1),
                                ox(1),
                                oy(2),
                                oz(3),
                                0.7f,
                                0.8f,
                                0.9f,
                                0.7f,
                                0.8f,
                                0.9f)),
                sink.calls);
    }

    @Test
    void drawSphere() {
        drawer.drawSphere(new btVector3(5.55, -1.25, 0.1), 0.3, new btVector3(1, 0.5, 0.25));
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawSphere", ox(5.55), oy(-1.25), oz(0.1), 0.3f, 1f, 0.5f, 0.25f)),
                sink.calls);
    }

    @Test
    void drawTriangle() {
        drawer.drawTriangle(
                new btVector3(5.55, 1, 0.1),
                new btVector3(2, 3, 4),
                new btVector3(-5, -6, -7),
                new btVector3(0.1, 0.2, 0.3),
                0.45);
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawTriangle",
                                ox(5.55),
                                oy(1),
                                oz(0.1),
                                ox(2),
                                oy(3),
                                oz(4),
                                ox(-5),
                                oy(-6),
                                oz(-7),
                                0.1f,
                                0.2f,
                                0.3f,
                                0.45f)),
                sink.calls);
    }

    @Test
    void drawContactPointOffsetsOnlyThePoint() {
        drawer.drawContactPoint(
                new btVector3(5.55, 1, 0.1),
                new btVector3(0.1, 0.9, -0.3),
                -0.015,
                7,
                new btVector3(1, 1, 0));
        assertEquals(
                List.of(
                        CaptureSink.line(
                                "drawContactPoint",
                                ox(5.55),
                                oy(1),
                                oz(0.1),
                                0.1f,
                                0.9f,
                                -0.3f,
                                -0.015f,
                                7,
                                1f,
                                1f,
                                0f)),
                sink.calls);
    }

    @Test
    void draw3dTextDrawsNothing() {
        drawer.draw3dText(new btVector3(1, 2, 3), "x");
        assertTrue(sink.calls.isEmpty());
    }

    /** Records the primitives the {@link btIDebugDraw} defaults emit, mapped as PZGL would. */
    private final class Expect extends btIDebugDraw {
        final List<String> out = new ArrayList<>();

        @Override
        public void drawLine(btVector3 from, btVector3 to, btVector3 color) {
            drawLine(from, to, color, color);
        }

        @Override
        public void drawLine(btVector3 from, btVector3 to, btVector3 fc, btVector3 tc) {
            out.add(
                    CaptureSink.line(
                            "drawLine",
                            ox(from.x),
                            oy(from.y),
                            oz(from.z),
                            ox(to.x),
                            oy(to.y),
                            oz(to.z),
                            n(fc.x),
                            n(fc.y),
                            n(fc.z),
                            n(tc.x),
                            n(tc.y),
                            n(tc.z)));
        }

        @Override
        public void drawSphere(btVector3 p, double radius, btVector3 c) {
            out.add(
                    CaptureSink.line(
                            "drawSphere",
                            ox(p.x),
                            oy(p.y),
                            oz(p.z),
                            n(radius),
                            n(c.x),
                            n(c.y),
                            n(c.z)));
        }

        @Override
        public void drawTriangle(
                btVector3 a, btVector3 b, btVector3 c, btVector3 color, double alpha) {
            out.add(
                    CaptureSink.line(
                            "drawTriangle",
                            ox(a.x),
                            oy(a.y),
                            oz(a.z),
                            ox(b.x),
                            oy(b.y),
                            oz(b.z),
                            ox(c.x),
                            oy(c.y),
                            oz(c.z),
                            n(color.x),
                            n(color.y),
                            n(color.z),
                            n(alpha)));
        }

        @Override
        public void drawContactPoint(
                btVector3 p, btVector3 nrm, double distance, int lifeTime, btVector3 color) {}

        @Override
        public void reportErrorWarning(String warningString) {}

        @Override
        public void draw3dText(btVector3 location, String textString) {}

        @Override
        public void setDebugMode(int debugMode) {}

        @Override
        public int getDebugMode() {
            return 0;
        }
    }

    @Test
    void drawCapsuleIsTheBaseDefaultWithTheCallersColour() {
        GLDebugDrawer.drawCapsule_onceGLDebugDraw = false;
        btTransform t = new btTransform(new btQuaternion(0.1, 0.2, 0.3, 0.9273618495495703));
        t.setOrigin(new btVector3(5.55, 1.2, 0.1));
        btVector3 colour = new btVector3(0.25, 0.5, 0.75);
        for (int upAxis = 0; upAxis < 3; upAxis++) {
            sink.calls.clear();
            drawer.drawCapsule(0.35, 0.8, upAxis, t, colour);
            Expect e = new Expect();
            e.drawCapsule(0.35, 0.8, upAxis, t, colour);
            assertFalse(e.out.isEmpty());
            assertEquals(e.out, sink.calls, "upAxis " + upAxis);
        }
        // PZGLDebugDrawer::drawCapsule does not go through GLDebugDrawer's logging override
        assertFalse(GLDebugDrawer.drawCapsule_onceGLDebugDraw);
    }
}
